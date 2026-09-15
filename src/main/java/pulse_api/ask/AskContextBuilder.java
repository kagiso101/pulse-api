package pulse_api.ask;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.CostDtos;
import pulse_api.entity.AlertEvent;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.entity.Prospect;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.BookvasTenantCacheRepository;
import pulse_api.repository.DailySummaryRepository;
import pulse_api.repository.KeyValue;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.CostService;
import pulse_api.service.MetricQueryService;
import pulse_api.service.OverviewService;
import pulse_api.service.RangeResolver;
import pulse_api.service.UptimeStatusService;

import java.util.List;

import static pulse_api.service.MetricQueryService.*;

/**
 * Assembles the compact, read-only context Ask reasons over (contract §2.10): latest snapshots for
 * the scope + range, open alerts, overdue prospects, latest summary, this month's costs. Plain
 * text, capped at ~24k characters (~6k tokens).
 */
@Component
@RequiredArgsConstructor
public class AskContextBuilder {

    static final int MAX_CHARS = 24_000;

    private final ProjectRepository projects;
    private final MetricQueryService metrics;
    private final UptimeStatusService uptime;
    private final AlertEventRepository alertEvents;
    private final OverviewService overview;
    private final DailySummaryRepository summaries;
    private final BookvasTenantCacheRepository tenantCache;
    private final CostService costs;
    private final RangeResolver ranges;

    @Transactional(readOnly = true)
    public String build(String projectSlug, String range) {
        var r = ranges.resolve(range);
        StringBuilder b = new StringBuilder();
        b.append("Now (Africa/Johannesburg): ").append(ranges.now().toLocalDateTime().withNano(0)).append('\n');
        b.append("Range: ").append(r.range()).append(" = ").append(r.startDay()).append(" to ").append(r.endDay()).append(" inclusive\n");

        List<Project> scope = projectSlug == null || projectSlug.isBlank()
                ? projects.findByActiveTrueOrderBySortOrderAscNameAsc()
                : projects.findBySlug(projectSlug).map(List::of).orElse(List.of());
        b.append("Scope: ").append(projectSlug == null || projectSlug.isBlank() ? "all projects" : projectSlug).append("\n\n");

        for (Project p : scope) {
            appendProject(b, p, r);
        }

        b.append("\nOpen alerts (unacknowledged):\n");
        List<AlertEvent> open = alertEvents.findByAcknowledgedAtIsNullOrderByFiredAtDesc(PageRequest.of(0, 20));
        if (open.isEmpty()) {
            b.append("  none\n");
        }
        for (AlertEvent e : open) {
            b.append("  - ").append(e.getFiredAt()).append(' ').append(e.getKind()).append(": ").append(e.getTitle())
                    .append(" — ").append(e.getDetail()).append('\n');
        }

        b.append("\nOverdue prospects:\n");
        List<Prospect> overdue = overview.overdueProspects(ranges.today());
        if (overdue.isEmpty()) {
            b.append("  none\n");
        }
        for (Prospect p : overdue.stream().limit(25).toList()) {
            b.append("  - ").append(p.getName()).append(p.getBusiness() == null ? "" : " (" + p.getBusiness() + ")")
                    .append(" status=").append(p.getStatus()).append(" next=").append(p.getNextAction())
                    .append(" due=").append(p.getNextActionDate()).append('\n');
        }

        summaries.findFirstByOrderBySummaryDateDesc().ifPresent(s ->
                b.append("\nLatest daily summary (").append(s.getSummaryDate()).append("): ").append(s.getBody()).append('\n'));

        CostDtos.CostReport cost = costs.report(null);
        b.append("\nCosts this month (").append(cost.month()).append("): total ").append(rands(cost.totalCents())).append('\n');
        for (CostDtos.ProviderRow row : cost.byProvider()) {
            b.append("  - ").append(row.provider()).append(' ').append(rands(row.amountCents())).append(" (").append(row.source()).append(")\n");
        }
        b.append("  trend: ");
        cost.trend().forEach(t -> b.append(t.month()).append('=').append(rands(t.totalCents())).append(' '));
        b.append('\n');

        String text = b.toString();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n[context truncated]" : text;
    }

    private void appendProject(StringBuilder b, Project p, RangeResolver.ResolvedRange r) {
        b.append("Project ").append(p.getName()).append(" (slug ").append(p.getSlug()).append(", kind ").append(p.getKind())
                .append(", status ").append(uptime.status(p)).append(")\n");
        if (metrics.hasGa4Data(p.getId())) {
            b.append("  visitors=").append(metrics.sum(p.getId(), ACTIVE_USERS, r))
                    .append(" sessions=").append(metrics.sum(p.getId(), SESSIONS, r))
                    .append(" pageViews=").append(metrics.sum(p.getId(), PAGE_VIEWS, r)).append('\n');
            b.append("  daily visitors: ");
            metrics.dailySeries(p.getId(), ACTIVE_USERS, r).forEach((d, v) -> b.append(d).append('=').append(v).append(' '));
            b.append('\n');
            List<KeyValue> pages = metrics.topDimensions(p.getId(), PAGE_VIEWS_BY_PATH, r, 5);
            if (!pages.isEmpty()) {
                b.append("  top pages: ");
                pages.forEach(kv -> b.append(kv.key()).append('=').append(kv.value().longValue()).append(' '));
                b.append('\n');
            }
            List<KeyValue> events = metrics.events(p.getId(), r);
            if (!events.isEmpty()) {
                b.append("  events: ");
                events.forEach(kv -> b.append(kv.key()).append('=').append(kv.value().longValue()).append(' '));
                b.append('\n');
            }
        } else {
            b.append("  no GA4 traffic data yet\n");
        }
        if (p.getKind() == Enums.ProjectKind.product) {
            b.append("  Bookvas: tenants=").append(metrics.latestValue(p.getId(), "tenants_total"))
                    .append(" founderSeatsUsed=").append(metrics.latestValue(p.getId(), "founder_seats_used"))
                    .append("/").append(metrics.latestValue(p.getId(), "founder_seats_total"))
                    .append(" emailFailed24h=").append(metrics.latestValue(p.getId(), "email_failed_24h"))
                    .append(" unresolvedPlatformErrors=").append(metrics.latestValue(p.getId(), "platform_events_unresolved"))
                    .append('\n');
            b.append("  bookings and deposit revenue: not available (Bookvas endpoint gap)\n");
            var tenants = tenantCache.findAllByOrderByTenantCreatedAtDesc();
            if (!tenants.isEmpty()) {
                b.append("  tenants: ");
                tenants.stream().limit(30).forEach(t -> b.append(t.getBusinessName()).append('[').append(t.getPlanCode())
                        .append('/').append(t.getSubscriptionStatus()).append(t.isFounder() ? ",founder" : "").append("] "));
                b.append('\n');
            }
        }
    }

    private static String rands(long cents) {
        return "R" + (cents / 100) + "." + String.format("%02d", Math.abs(cents % 100));
    }
}
