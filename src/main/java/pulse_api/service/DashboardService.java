package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.ProjectDashboardDto;
import pulse_api.dto.ProjectDashboardDto.*;
import pulse_api.entity.Enums;
import pulse_api.entity.MetricSnapshot;
import pulse_api.entity.Project;
import pulse_api.repository.BookvasPlatformEventRepository;
import pulse_api.repository.BookvasTenantCacheRepository;
import pulse_api.repository.KeyValue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static pulse_api.service.MetricQueryService.*;

/** Contract §2.3 — the per-project view, sections by kind. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    static final List<String> FUNNEL = List.of("booking_started", "slot_selected", "deposit_initiated", "purchase");

    private final ProjectService projectService;
    private final MetricQueryService metrics;
    private final UptimeStatusService uptime;
    private final DeployService deploys;
    private final BookvasTenantCacheRepository tenantCache;
    private final BookvasPlatformEventRepository platformEvents;
    private final RangeResolver ranges;

    @Transactional(readOnly = true)
    public ProjectDashboardDto dashboard(String slug, String range) {
        var r = ranges.resolve(range);
        Project p = projectService.requireBySlug(slug);
        var header = new Header(uptime.status(p), uptime.uptimePct(p, r.startInstant()), deploys.latest(p.getId()),
                uptime.latestLatencyMs(p));
        return new ProjectDashboardDto(projectService.toDto(p), header, traffic(p.getId(), r),
                deploys.list(p.getId(), 10),
                p.getKind() == Enums.ProjectKind.product ? bookvas(p.getId(), r) : null);
    }

    public Traffic traffic(UUID projectId, RangeResolver.ResolvedRange r) {
        boolean available = metrics.hasGa4Data(projectId);
        if (!available) {
            return new Traffic(List.of(), List.of(), List.of(), List.of(), false);
        }
        Map<LocalDate, Long> users = metrics.dailySeries(projectId, ACTIVE_USERS, r);
        Map<LocalDate, Long> sessions = metrics.dailySeries(projectId, SESSIONS, r);
        Map<LocalDate, Long> views = metrics.dailySeries(projectId, PAGE_VIEWS, r);
        List<SeriesPoint> series = new ArrayList<>();
        for (LocalDate day : r.days()) {
            series.add(new SeriesPoint(day, users.get(day), sessions.get(day), views.get(day)));
        }
        List<TopPage> topPages = metrics.topDimensions(projectId, PAGE_VIEWS_BY_PATH, r, 10).stream()
                .map(kv -> new TopPage(kv.key(), kv.value().longValue())).toList();
        List<SourceRow> sources = metrics.topDimensions(projectId, SESSIONS_BY_SOURCE, r, 10).stream()
                .map(kv -> new SourceRow(kv.key(), kv.value().longValue())).toList();
        List<KeyEvent> events = metrics.events(projectId, r).stream()
                .map(kv -> new KeyEvent(kv.key(), kv.value().longValue())).toList();
        return new Traffic(series, topPages, sources, events, true);
    }

    private BookvasSection bookvas(UUID projectId, RangeResolver.ResolvedRange r) {
        return new BookvasSection(funnel(projectId, r),
                // Bookvas has no platform-wide payments endpoint yet (contract §5 gaps 2-3)
                new Revenue(null, null, null, false),
                new Founder(metrics.latestValue(projectId, "founder_seats_used"), metrics.latestValue(projectId, "founder_seats_total")),
                tenantCache.findAllByOrderByTenantCreatedAtDesc().stream().map(t -> new TenantRow(t.getTenantId(),
                        t.getSlug(), t.getBusinessName(), t.getStatus(), t.getSubscriptionId(), t.getPlanCode(),
                        t.getSubscriptionStatus(), t.isFounder(), t.getGraceUntil(), t.getTenantCreatedAt())).toList(),
                emailHealth(projectId),
                platformEvents.findAllByOrderByHappenedAtDesc(PageRequest.of(0, 20)).stream()
                        .map(e -> new PlatformEventDto(e.getId(), e.getHappenedAt(), e.getTenantName(), e.getEventType(),
                                e.getSeverity(), e.getMessage(), e.getResolvedAt())).toList());
    }

    private Funnel funnel(UUID projectId, RangeResolver.ResolvedRange r) {
        if (!metrics.hasGa4Data(projectId)) {
            List<FunnelStep> steps = FUNNEL.stream().map(e -> new FunnelStep(e, null)).toList();
            return new Funnel(steps, dropoffs(steps), true, "No GA4 data for the Bookvas property yet.");
        }
        List<FunnelStep> steps = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String event : FUNNEL) {
            Long count = metrics.sumOrNull(projectId, EVENT_PREFIX + event, r);
            if (count == null) {
                missing.add(event);
            }
            steps.add(new FunnelStep(event, count));
        }
        String note = missing.isEmpty() ? null
                : "Bookvas does not emit these GA4 events yet: " + String.join(", ", missing)
                + " (see specs/BOOKVAS-API-GAPS.md). Showing the last known state.";
        return new Funnel(steps, dropoffs(steps), !missing.isEmpty(), note);
    }

    static List<Dropoff> dropoffs(List<FunnelStep> steps) {
        List<Dropoff> out = new ArrayList<>();
        for (int i = 1; i < steps.size(); i++) {
            Long from = steps.get(i - 1).count();
            Long to = steps.get(i).count();
            Double pct = from == null || to == null || from == 0 ? null
                    : Math.round((1.0 - (double) to / from) * 1000.0) / 10.0;
            out.add(new Dropoff(steps.get(i - 1).event(), steps.get(i).event(), pct));
        }
        return out;
    }

    private EmailHealth emailHealth(UUID projectId) {
        MetricSnapshot sent24 = metrics.latest(projectId, "email_sent_24h");
        if (sent24 == null) {
            return null;
        }
        Long lastSentEpoch = metrics.latestValue(projectId, "email_last_sent_at_epoch");
        Long smtp = metrics.latestValue(projectId, "email_smtp_configured");
        return new EmailHealth(sent24.getMetricValue().longValue(), zero(metrics.latestValue(projectId, "email_failed_24h")),
                zero(metrics.latestValue(projectId, "email_sent_7d")), zero(metrics.latestValue(projectId, "email_failed_7d")),
                lastSentEpoch == null || lastSentEpoch == 0 ? null : Instant.ofEpochSecond(lastSentEpoch),
                smtp != null && smtp > 0);
    }

    private static long zero(Long v) {
        return v == null ? 0 : v;
    }

    /** Exposed for Ask's context: the top pages as plain key/values. */
    public List<KeyValue> topPages(UUID projectId, RangeResolver.ResolvedRange r, int limit) {
        return metrics.topDimensions(projectId, PAGE_VIEWS_BY_PATH, r, limit);
    }
}
