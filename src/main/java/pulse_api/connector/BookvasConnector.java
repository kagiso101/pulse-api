package pulse_api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.entity.BookvasPlatformEvent;
import pulse_api.entity.BookvasTenantCache;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.exception.ApiException;
import pulse_api.repository.BookvasPlatformEventRepository;
import pulse_api.repository.BookvasTenantCacheRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.MetricQueryService;
import pulse_api.service.RangeResolver;
import pulse_api.service.SnapshotWriter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bookvas super-admin connector (spec §4.2, contract §5). Writes platform-level snapshots on the
 * Bookvas registry project, caches tenants+subscriptions and the last 100 platform events, and
 * exposes the three tenant actions. Bookings/payments/revenue have no endpoint yet → not written
 * (see specs/BOOKVAS-API-GAPS.md).
 */
@Slf4j
@Component
public class BookvasConnector implements Connector {

    private final BookvasClient client;
    private final ObjectMapper json;
    private final ProjectRepository projects;
    private final BookvasTenantCacheRepository tenantCache;
    private final BookvasPlatformEventRepository platformEvents;
    private final SnapshotWriter writer;
    private final RangeResolver ranges;
    private final String projectSlug;

    public BookvasConnector(BookvasClient client, ObjectMapper upstreamJson, ProjectRepository projects,
                            BookvasTenantCacheRepository tenantCache, BookvasPlatformEventRepository platformEvents,
                            SnapshotWriter writer, RangeResolver ranges,
                            @Value("${app.bookvas.project-slug:bookvas}") String projectSlug) {
        this.client = client;
        this.json = upstreamJson;
        this.projects = projects;
        this.tenantCache = tenantCache;
        this.platformEvents = platformEvents;
        this.writer = writer;
        this.ranges = ranges;
        this.projectSlug = projectSlug;
    }

    @Override
    public String source() {
        return MetricQueryService.SOURCE_BOOKVAS;
    }

    @Override
    public boolean configured() {
        return client.configured();
    }

    /** The registry row Bookvas numbers are attached to: slug {@code bookvas}, else the first product. */
    public Optional<Project> project() {
        return projects.findBySlug(projectSlug).or(() ->
                projects.findByActiveTrueAndKind(Enums.ProjectKind.product).stream().findFirst());
    }

    @Override
    @Transactional
    public FetchResult fetch(FetchWindow window) {
        Project project = project().orElse(null);
        if (project == null) {
            return FetchResult.of(0, List.of(source() + ": no registry project with slug '" + projectSlug + "'"));
        }
        UUID pid = project.getId();
        LocalDate today = ranges.today();
        int written = 0;
        List<String> errors = new ArrayList<>();

        JsonNode tenants = null;
        JsonNode subscriptions = null;
        try {
            tenants = client.get("/api/platform/tenants");
            subscriptions = client.get("/api/platform/subscriptions");
            written += writeTenantSnapshots(pid, today, tenants, subscriptions);
            written += cacheTenants(tenants, subscriptions);
        } catch (Exception e) {
            errors.add(source() + " tenants/subscriptions: " + FetchResult.describe(e));
        }
        try {
            JsonNode pricing = client.get("/api/platform/pricing-config");
            written += writer.day(pid, source(), "founder_seats_used", null, today, pricing.path("founderSeatsUsed").asLong(0));
            written += writer.day(pid, source(), "founder_seats_total", null, today, pricing.path("founderSeatsTotal").asLong(0));
        } catch (Exception e) {
            errors.add(source() + " pricing-config: " + FetchResult.describe(e));
        }
        try {
            JsonNode email = client.get("/api/platform/operations/email-summary");
            written += writer.day(pid, source(), "email_sent_24h", null, today, email.path("sent24h").asLong(0));
            written += writer.day(pid, source(), "email_failed_24h", null, today, email.path("failed24h").asLong(0));
            written += writer.day(pid, source(), "email_sent_7d", null, today, email.path("sent7d").asLong(0));
            written += writer.day(pid, source(), "email_failed_7d", null, today, email.path("failed7d").asLong(0));
            written += writer.day(pid, source(), "email_smtp_configured", null, today, email.path("smtpConfigured").asBoolean(false) ? 1 : 0);
            Instant lastSent = instant(email.path("lastSentAt"));
            written += writer.day(pid, source(), "email_last_sent_at_epoch", null, today, lastSent == null ? 0 : lastSent.getEpochSecond());
        } catch (Exception e) {
            errors.add(source() + " email-summary: " + FetchResult.describe(e));
        }
        try {
            String from = today.minusDays(7).toString();
            JsonNode events = client.get("/api/platform/operations/events?from=" + from + "&to=" + today
                    + "&page=0&size=100&unresolvedOnly=false");
            written += writer.day(pid, source(), "platform_events_unresolved", null, today, events.path("unresolvedErrorCount").asLong(0));
            written += cacheEvents(events.path("events"));
        } catch (Exception e) {
            errors.add(source() + " events: " + FetchResult.describe(e));
        }
        return FetchResult.of(written, errors);
    }

    private int writeTenantSnapshots(UUID pid, LocalDate today, JsonNode tenants, JsonNode subscriptions) {
        int written = writer.day(pid, source(), "tenants_total", null, today, tenants.size());
        Map<String, Long> byStatus = new HashMap<>();
        for (JsonNode t : tenants) {
            byStatus.merge(t.path("status").asText("UNKNOWN"), 1L, Long::sum);
        }
        for (var e : byStatus.entrySet()) {
            written += writer.day(pid, source(), "tenants:status", e.getKey(), today, e.getValue());
        }
        Map<String, Long> subsByStatus = new HashMap<>();
        for (JsonNode s : subscriptions) {
            subsByStatus.merge(s.path("status").asText("UNKNOWN"), 1L, Long::sum);
        }
        for (var e : subsByStatus.entrySet()) {
            written += writer.day(pid, source(), "subscriptions:status", e.getKey(), today, e.getValue());
        }
        return written;
    }

    /** One cache row per tenant, joined with its most recent subscription; remembers the previous status for the grace alert. */
    private int cacheTenants(JsonNode tenants, JsonNode subscriptions) {
        Map<String, JsonNode> subByTenant = new HashMap<>();
        for (JsonNode s : subscriptions) {
            String tenantId = s.path("tenantId").asText(null);
            if (tenantId == null) continue;
            JsonNode existing = subByTenant.get(tenantId);
            if (existing == null || text(s, "createdAt").compareTo(text(existing, "createdAt")) > 0) {
                subByTenant.put(tenantId, s);
            }
        }
        int written = 0;
        for (JsonNode t : tenants) {
            UUID tenantId = uuid(t.path("id"));
            if (tenantId == null) continue;
            BookvasTenantCache row = tenantCache.findById(tenantId).orElseGet(BookvasTenantCache::new);
            row.setTenantId(tenantId);
            row.setSlug(t.path("slug").asText(""));
            row.setBusinessName(t.path("businessName").asText(""));
            row.setStatus(t.path("status").asText(null));
            row.setActive(t.hasNonNull("active") ? t.get("active").asBoolean() : null);
            row.setTenantCreatedAt(instant(t.path("createdAt")));
            JsonNode s = subByTenant.get(tenantId.toString());
            String newStatus = s == null ? null : s.path("status").asText(null);
            if (!java.util.Objects.equals(newStatus, row.getSubscriptionStatus())) {
                row.setPreviousSubscriptionStatus(row.getSubscriptionStatus());
            }
            row.setSubscriptionStatus(newStatus);
            row.setSubscriptionId(s == null ? null : uuid(s.path("id")));
            row.setPlanCode(s == null ? null : s.path("planCode").asText(null));
            row.setFounder(s != null && s.path("isFounder").asBoolean(false));
            row.setGraceUntil(s == null ? null : instant(s.path("graceUntil")));
            row.setCapturedAt(Instant.now());
            tenantCache.save(row);
            written++;
        }
        return written;
    }

    private int cacheEvents(JsonNode events) {
        int written = 0;
        for (JsonNode e : events) {
            UUID id = uuid(e.path("id"));
            Instant happenedAt = instant(e.path("happenedAt"));
            if (id == null || happenedAt == null) continue;
            BookvasPlatformEvent row = platformEvents.findById(id).orElseGet(BookvasPlatformEvent::new);
            row.setId(id);
            row.setHappenedAt(happenedAt);
            row.setTenantId(uuid(e.path("tenantId")));
            row.setTenantName(e.path("tenantName").asText(null));
            row.setEventType(e.path("eventType").asText("UNKNOWN"));
            row.setSeverity(e.path("severity").asText("INFO"));
            row.setMessage(e.path("message").asText(""));
            row.setResolvedAt(instant(e.path("resolvedAt")));
            row.setRaw(json.convertValue(e, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            row.setCapturedAt(Instant.now());
            platformEvents.save(row);
            written++;
        }
        return written;
    }

    // ---- actions (contract §2.9) ---------------------------------------------------------

    public String extendGrace(String subscriptionId, int days) {
        requireConfigured();
        JsonNode r = client.post("/api/platform/subscriptions/" + subscriptionId + "/extend-grace", Map.of("days", days));
        return "Grace extended by " + days + " day(s)" + suffix(r);
    }

    public String compPeriod(String subscriptionId, int days) {
        requireConfigured();
        JsonNode r = client.post("/api/platform/subscriptions/" + subscriptionId + "/comp-period", Map.of("days", days));
        return "Complimentary period of " + days + " day(s) applied" + suffix(r);
    }

    public String toggleFounder(String tenantId) {
        requireConfigured();
        JsonNode r = client.post("/api/platform/subscriptions/tenant/" + tenantId + "/toggle-founder", null);
        return "Founder flag toggled" + suffix(r);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw ApiException.notConfigured("Bookvas connector");
        }
    }

    private static String suffix(JsonNode r) {
        if (r == null || r.isNull() || r.isMissingNode()) return ".";
        if (r.hasNonNull("status")) return " (status now " + r.get("status").asText() + ").";
        if (r.hasNonNull("isFounder")) return " (isFounder now " + r.get("isFounder").asBoolean() + ").";
        return ".";
    }

    // ---- parsing helpers: Bookvas serialises LocalDateTime without a zone; treat as UTC ------

    private static String text(JsonNode n, String field) {
        return n.path(field).asText("");
    }

    private static UUID uuid(JsonNode n) {
        try {
            return n == null || n.isNull() || n.isMissingNode() ? null : UUID.fromString(n.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Instant instant(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || n.asText().isBlank()) {
            return null;
        }
        String raw = n.asText();
        try {
            return Instant.parse(raw);
        } catch (Exception notInstant) {
            try {
                return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
