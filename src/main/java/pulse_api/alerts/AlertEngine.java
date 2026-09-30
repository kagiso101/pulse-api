package pulse_api.alerts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.entity.AlertEvent;
import pulse_api.entity.AlertRule;
import pulse_api.entity.AlertState;
import pulse_api.entity.BookvasPlatformEvent;
import pulse_api.entity.BookvasTenantCache;
import pulse_api.entity.Enums;
import pulse_api.entity.Enums.AlertKind;
import pulse_api.entity.MetricSnapshot;
import pulse_api.entity.Project;
import pulse_api.entity.Prospect;
import pulse_api.entity.UptimeCheck;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.AlertRuleRepository;
import pulse_api.repository.AlertStateRepository;
import pulse_api.repository.BookvasPlatformEventRepository;
import pulse_api.repository.BookvasTenantCacheRepository;
import pulse_api.repository.MetricSnapshotRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.repository.ProspectRepository;
import pulse_api.repository.UptimeCheckRepository;
import pulse_api.service.RangeResolver;
import pulse_api.service.UptimeStatusService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates the six rule kinds (spec §6) against snapshots and caches. Two guards make every
 * condition fire exactly once: {@code alert_state} remembers the last value/state seen (so
 * increments and transitions are detected), and an unacknowledged event with the same dedupe key
 * blocks a refire. Delivery goes through {@link Notifier}; a failed send still records the event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEngine {

    private static final String PURCHASE_EVENT = "event:purchase";
    // OPS-VISIBILITY: more Bookvas events of one type than this in one evaluation → one summary alert
    static final int BATCH_THRESHOLD = 5;
    private static final int BATCH_PREVIEW = 5;
    private static final BigDecimal DEFAULT_EMAIL_THRESHOLD = BigDecimal.valueOf(3);

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final AlertStateRepository states;
    private final MetricSnapshotRepository snapshots;
    private final UptimeCheckRepository uptimeChecks;
    private final BookvasTenantCacheRepository tenantCache;
    private final BookvasPlatformEventRepository platformEvents;
    private final ProspectRepository prospects;
    private final ProjectRepository projects;
    private final Notifier notifier;
    private final RangeResolver ranges;

    /** A condition that should produce one alert event. */
    record Candidate(String dedupeKey, UUID projectId, String title, String detail, Map<String, Object> payload) {}

    @Transactional
    public int evaluate() {
        return evaluate(EnumSet.allOf(AlertKind.class));
    }

    @Transactional
    public int evaluate(Set<AlertKind> kinds) {
        int fired = 0;
        for (AlertRule rule : rules.findByEnabledTrue()) {
            if (!kinds.contains(rule.getKind())) {
                continue;
            }
            try {
                for (Candidate c : candidates(rule)) {
                    if (fire(rule, c)) {
                        fired++;
                    }
                }
            } catch (Exception e) {
                log.warn("Alert rule {} evaluation failed: {}", rule.getKind(), e.toString());
            }
        }
        return fired;
    }

    List<Candidate> candidates(AlertRule rule) {
        return switch (rule.getKind()) {
            case deposit_paid -> depositPaid(rule);
            case founder_seat_claimed -> founderSeatClaimed(rule);
            case site_down -> siteDown(rule);
            case email_failures -> emailFailures(rule);
            case tenant_grace -> tenantGrace(rule);
            case prospect_overdue -> prospectOverdue();
            // OPS-VISIBILITY: one Pulse alert per Bookvas platform event of the matching type
            case checkout_stalled -> bookvasOpsEvent(rule, "CHECKOUT_STALLED");
            case payment_provider_degraded -> bookvasOpsEvent(rule, "PAYMENT_PROVIDER_DEGRADED");
            case signup_rate_limited -> bookvasOpsEvent(rule, "SIGNUP_RATE_LIMITED");
            case plan_price_changed -> bookvasOpsEvent(rule, "PLAN_PRICE_CHANGED");
            case merchant_verification_stalled -> bookvasOpsEvent(rule, "MERCHANT_VERIFICATION_STALLED");
        };
    }

    private boolean fire(AlertRule rule, Candidate c) {
        if (events.existsByDedupeKeyAndAcknowledgedAtIsNull(c.dedupeKey())) {
            return false;
        }
        AlertEvent event = new AlertEvent();
        event.setRuleId(rule.getId());
        event.setKind(rule.getKind());
        event.setProjectId(c.projectId());
        event.setFiredAt(Instant.now());
        event.setTitle(c.title());
        event.setDetail(c.detail() == null ? "" : c.detail());
        event.setPayload(c.payload());
        event.setDedupeKey(c.dedupeKey());
        String projectName = c.projectId() == null ? null
                : projects.findById(c.projectId()).map(Project::getName).orElse(null);
        AlertMessage message = AlertMessage.of(rule.getKind(), c.title(), c.detail(), c.payload(), projectName);
        Notifier.Delivery delivery = notifier.notify(rule.getChannel(), message, rule.getKind() == AlertKind.site_down);
        event.setDelivered(delivery.delivered());
        event.setChannel(delivery.channelUsed());
        events.save(event);
        log.info("AUDIT alert fired kind={} key={} channel={} delivered={}", rule.getKind(), c.dedupeKey(),
                delivery.channelUsed(), delivery.delivered());
        return true;
    }

    // ---- rule kinds ----------------------------------------------------------------------

    /** New GA4 purchase events today or yesterday since the last evaluation (first sight = baseline). */
    private List<Candidate> depositPaid(AlertRule rule) {
        List<Candidate> out = new ArrayList<>();
        LocalDate today = ranges.today();
        for (Project p : bookvasProjects(rule)) {
            for (LocalDate day : List.of(today.minusDays(1), today)) {
                BigDecimal current = snapshots.findByProjectIdAndMetricKeyAndPeriodStart(p.getId(), PURCHASE_EVENT, day)
                        .stream().map(MetricSnapshot::getMetricValue).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!snapshots.existsByProjectIdAndMetricKeyAndPeriodStartBetween(p.getId(), PURCHASE_EVENT, day, day)) {
                    continue;
                }
                String key = "deposit_paid:" + p.getId() + ":" + day;
                BigDecimal previous = numState(key);
                if (previous != null && current.compareTo(previous) > 0) {
                    long delta = current.subtract(previous).longValue();
                    out.add(new Candidate(key + ":" + current, p.getId(), "Deposit paid",
                            delta + " new Bookvas purchase" + (delta == 1 ? "" : "s") + " on " + day,
                            Map.of("projectId", p.getId().toString(), "day", day.toString(), "newPurchases", delta,
                                    "totalToday", current.longValue())));
                }
                saveNum(key, current);
            }
        }
        return out;
    }

    private List<Candidate> founderSeatClaimed(AlertRule rule) {
        List<Candidate> out = new ArrayList<>();
        for (Project p : bookvasProjects(rule)) {
            MetricSnapshot used = snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(p.getId(), "founder_seats_used").orElse(null);
            if (used == null) {
                continue;
            }
            Long total = snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(p.getId(), "founder_seats_total")
                    .map(s -> s.getMetricValue().longValue()).orElse(null);
            String key = "founder_seats_used:" + p.getId();
            BigDecimal previous = numState(key);
            BigDecimal current = used.getMetricValue();
            if (previous != null && current.compareTo(previous) > 0) {
                out.add(new Candidate(key + ":" + current.longValue(), p.getId(), "Founder seat claimed",
                        "Founder seats used: " + current.longValue() + (total == null ? "" : " / " + total),
                        Map.of("projectId", p.getId().toString(), "used", current.longValue(),
                                "total", total == null ? -1 : total)));
            }
            saveNum(key, current);
        }
        return out;
    }

    /** Transition to "down" (N consecutive failures, default 3) per project target; recovery resets. */
    private List<Candidate> siteDown(AlertRule rule) {
        List<Candidate> out = new ArrayList<>();
        int needed = rule.getThreshold() == null ? UptimeStatusService.CONSECUTIVE_FAILURES_FOR_DOWN
                : Math.max(1, rule.getThreshold().intValue());
        for (Project p : projects.findByActiveTrueOrderBySortOrderAscNameAsc()) {
            if (rule.getProjectId() != null && !rule.getProjectId().equals(p.getId())) {
                continue;
            }
            for (Enums.UptimeTarget target : Enums.UptimeTarget.values()) {
                String url = target == Enums.UptimeTarget.site ? p.getSiteUrl() : p.getApiHealthUrl();
                if (url == null || url.isBlank()) {
                    continue;
                }
                List<UptimeCheck> recent = uptimeChecks.findTop3ByProjectIdAndTargetOrderByCheckedAtDesc(p.getId(), target);
                boolean down = recent.size() >= Math.min(needed, 3)
                        && recent.stream().limit(needed).noneMatch(UptimeCheck::isOk);
                String key = "site_down:" + p.getId() + ":" + target;
                String previous = textState(key);
                if (down && !"down".equals(previous)) {
                    String error = recent.isEmpty() ? "" : String.valueOf(recent.get(0).getError());
                    out.add(new Candidate(key, p.getId(), p.getName() + " " + target + " is down",
                            url + " failed " + needed + " consecutive checks (" + error + ")",
                            Map.of("projectId", p.getId().toString(), "target", target.name(), "url", url,
                                    "lastError", error)));
                }
                saveText(key, down ? "down" : "up");
            }
        }
        return out;
    }

    /** failed24h above threshold; refires only when the count grows past the last alerted value. */
    private List<Candidate> emailFailures(AlertRule rule) {
        List<Candidate> out = new ArrayList<>();
        BigDecimal threshold = rule.getThreshold() == null ? DEFAULT_EMAIL_THRESHOLD : rule.getThreshold();
        for (Project p : bookvasProjects(rule)) {
            MetricSnapshot failed = snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(p.getId(), "email_failed_24h").orElse(null);
            if (failed == null) {
                continue;
            }
            String key = "email_failures:" + p.getId();
            BigDecimal current = failed.getMetricValue();
            if (current.compareTo(threshold) > 0) {
                BigDecimal lastAlerted = numState(key);
                if (lastAlerted == null || current.compareTo(lastAlerted) > 0) {
                    out.add(new Candidate(key, p.getId(), "Bookvas email failures",
                            current.longValue() + " emails failed in the last 24h (threshold " + threshold.longValue() + ")",
                            Map.of("projectId", p.getId().toString(), "failed24h", current.longValue(),
                                    "threshold", threshold.longValue())));
                    saveNum(key, current);
                }
            } else {
                states.deleteById(key);
            }
        }
        return out;
    }

    /** A cached subscription whose status became GRACE since last time. */
    private List<Candidate> tenantGrace(AlertRule rule) {
        List<Candidate> out = new ArrayList<>();
        UUID projectId = rule.getProjectId() != null ? rule.getProjectId()
                : bookvasProjects(rule).stream().map(Project::getId).findFirst().orElse(null);
        for (BookvasTenantCache t : tenantCache.findAll()) {
            String key = "tenant_grace:" + t.getTenantId();
            String status = t.getSubscriptionStatus() == null ? "" : t.getSubscriptionStatus().toUpperCase();
            String previous = textState(key);
            if ("GRACE".equals(status) && !"GRACE".equals(previous)) {
                out.add(new Candidate(key, projectId, t.getBusinessName() + " entered grace",
                        "Subscription " + (t.getPlanCode() == null ? "" : t.getPlanCode() + " ")
                                + "is in GRACE" + (t.getGraceUntil() == null ? "" : " until " + t.getGraceUntil()),
                        Map.of("tenantId", t.getTenantId().toString(), "tenantSlug", t.getSlug(),
                                "subscriptionId", t.getSubscriptionId() == null ? "" : t.getSubscriptionId().toString(),
                                "graceUntil", t.getGraceUntil() == null ? "" : t.getGraceUntil().toString())));
            }
            saveText(key, status);
        }
        return out;
    }

    /** In-app only: next_action_date before today (SA time) for prospects still in the pipeline. */
    private List<Candidate> prospectOverdue() {
        List<Candidate> out = new ArrayList<>();
        LocalDate today = ranges.today();
        for (Prospect p : prospects.findByNextActionDateBeforeAndStatusNotInOrderByNextActionDateAsc(today,
                EnumSet.of(Enums.ProspectStatus.tenant, Enums.ProspectStatus.declined))) {
            String key = "prospect_overdue:" + p.getId();
            String due = p.getNextActionDate().toString();
            if (!due.equals(textState(key))) {
                out.add(new Candidate(key, null, "Prospect overdue: " + p.getName(),
                        (p.getNextAction() == null ? "Follow up" : p.getNextAction()) + " was due " + due,
                        Map.of("prospectId", p.getId().toString(), "nextActionDate", due,
                                "status", p.getStatus().name())));
                saveText(key, due);
            }
        }
        return out;
    }

    // ---- OPS-VISIBILITY: Bookvas platform events → alerts --------------------------------------

    /**
     * Walks the ingested Bookvas events of one type forward from the last one alerted on. Each
     * event becomes one candidate keyed on the Bookvas event id, so the same event can never fire
     * twice even across restarts. First sight looks back one day, not forever.
     */
    private List<Candidate> bookvasOpsEvent(AlertRule rule, String eventType) {
        List<Candidate> out = new ArrayList<>();
        String stateKey = "bookvas_event:" + eventType;
        String last = textState(stateKey);
        Instant since = last != null ? Instant.parse(last) : Instant.now().minus(Duration.ofDays(1));
        UUID projectId = rule.getProjectId() != null ? rule.getProjectId()
                : bookvasProjects(rule).stream().map(Project::getId).findFirst().orElse(null);

        Instant newest = since;
        List<BookvasPlatformEvent> fresh = platformEvents.findTop50ByEventTypeAndHappenedAtGreaterThanOrderByHappenedAtAsc(eventType, since);
        for (BookvasPlatformEvent e : fresh) {
            if (e.getHappenedAt().isAfter(newest)) {
                newest = e.getHappenedAt();
            }
        }
        if (fresh.size() > BATCH_THRESHOLD) {
            // a burst (a backfill, a provider outage) becomes ONE email, not fifty — the individual
            // rows are all on the Bookvas Operations page anyway
            out.add(batch(eventType, fresh, projectId, newest));
        } else {
            for (BookvasPlatformEvent e : fresh) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("bookvasEventId", e.getId().toString());
                payload.put("eventType", e.getEventType());
                payload.put("severity", e.getSeverity());
                payload.put("happenedAt", e.getHappenedAt().toString());
                if (e.getTenantId() != null) payload.put("tenantId", e.getTenantId().toString());
                if (e.getTenantName() != null) payload.put("tenant", e.getTenantName());
                out.add(new Candidate("bookvas_event:" + e.getId(), projectId, opsTitle(eventType, e), e.getMessage(), payload));
            }
        }
        if (newest.isAfter(since)) {
            saveText(stateKey, newest.toString());
        }
        return out;
    }

    /** More than {@link #BATCH_THRESHOLD} events of one type in one evaluation collapse into a single alert. */
    private static Candidate batch(String eventType, List<BookvasPlatformEvent> events, UUID projectId, Instant newest) {
        BookvasPlatformEvent first = events.get(0);
        BookvasPlatformEvent last = events.get(events.size() - 1);
        StringBuilder detail = new StringBuilder();
        detail.append(events.size()).append(" ").append(opsNoun(eventType)).append(" were reported between ")
              .append(first.getHappenedAt()).append(" and ").append(last.getHappenedAt()).append(".\n\n");
        int shown = Math.min(BATCH_PREVIEW, events.size());
        for (int i = 0; i < shown; i++) {
            detail.append("• ").append(events.get(i).getMessage()).append('\n');
        }
        if (events.size() > shown) {
            detail.append("…and ").append(events.size() - shown).append(" more. See the Operations page in the Bookvas admin.");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("count", events.size());
        payload.put("firstAt", first.getHappenedAt().toString());
        payload.put("lastAt", last.getHappenedAt().toString());
        String title = events.size() + " " + opsNoun(eventType) + " on Bookvas";
        return new Candidate("bookvas_event_batch:" + eventType + ":" + newest, projectId, title, detail.toString(), payload);
    }

    private static String opsNoun(String eventType) {
        return switch (eventType) {
            case "CHECKOUT_STALLED" -> "stalled checkouts";
            case "PAYMENT_PROVIDER_DEGRADED" -> "provider-degraded alerts";
            case "SIGNUP_RATE_LIMITED" -> "refused signups";
            case "PLAN_PRICE_CHANGED" -> "plan price changes";
            case "MERCHANT_VERIFICATION_STALLED" -> "stalled merchant verifications";
            default -> eventType.toLowerCase().replace('_', ' ') + " events";
        };
    }

    private static String opsTitle(String eventType, BookvasPlatformEvent e) {
        String tenant = e.getTenantName() == null || e.getTenantName().isBlank() ? "" : " — " + e.getTenantName();
        return switch (eventType) {
            case "CHECKOUT_STALLED" -> "Bookvas checkout stalled" + tenant;
            case "PAYMENT_PROVIDER_DEGRADED" -> "PayFast checkouts are stalling on Bookvas";
            case "SIGNUP_RATE_LIMITED" -> "A Bookvas signup was refused by the rate limiter";
            case "PLAN_PRICE_CHANGED" -> "Bookvas plan price changed";
            case "MERCHANT_VERIFICATION_STALLED" -> "Merchant verification stalled" + tenant;
            default -> "Bookvas: " + eventType.toLowerCase().replace('_', ' ') + tenant;
        };
    }

    // ---- helpers ---------------------------------------------------------------------------

    private List<Project> bookvasProjects(AlertRule rule) {
        if (rule.getProjectId() != null) {
            return projects.findById(rule.getProjectId()).map(List::of).orElse(List.of());
        }
        return projects.findByActiveTrueAndKind(Enums.ProjectKind.product);
    }

    private BigDecimal numState(String key) {
        return states.findById(key).map(AlertState::getNumValue).orElse(null);
    }

    private String textState(String key) {
        return states.findById(key).map(AlertState::getTextValue).orElse(null);
    }

    private void saveNum(String key, BigDecimal value) {
        AlertState s = states.findById(key).orElseGet(AlertState::new);
        if (s.getStateKey() == null || !Objects.equals(s.getNumValue(), value)) {
            s.setStateKey(key);
            s.setNumValue(value);
            s.setUpdatedAt(Instant.now());
            states.save(s);
        }
    }

    private void saveText(String key, String value) {
        AlertState s = states.findById(key).orElseGet(AlertState::new);
        if (s.getStateKey() == null || !Objects.equals(s.getTextValue(), value)) {
            s.setStateKey(key);
            s.setTextValue(value);
            s.setUpdatedAt(Instant.now());
            states.save(s);
        }
    }
}
