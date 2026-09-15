package pulse_api.dto;

import pulse_api.entity.Enums;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Contract §2.3 {@code ProjectDashboard}. */
public record ProjectDashboardDto(
        ProjectDto project,
        Header header,
        Traffic traffic,
        List<DeployEventDto> deploys,
        BookvasSection bookvas
) {
    public record Header(Enums.UpState status, Double uptimePct, DeployEventDto lastDeploy, Integer latencyMs) {}

    public record Traffic(List<SeriesPoint> series, List<TopPage> topPages, List<SourceRow> sources,
                          List<KeyEvent> keyEvents, boolean available) {}

    public record SeriesPoint(LocalDate date, long activeUsers, long sessions, long pageViews) {}

    public record TopPage(String path, long views) {}

    public record SourceRow(String source, long sessions) {}

    public record KeyEvent(String event, long count) {}

    public record BookvasSection(Funnel funnel, Revenue revenue, Founder founder, List<TenantRow> tenants,
                                 EmailHealth emailHealth, List<PlatformEventDto> events) {}

    public record Funnel(List<FunnelStep> steps, List<Dropoff> dropoffs, boolean stale, String note) {}

    public record FunnelStep(String event, Long count) {}

    public record Dropoff(String from, String to, Double pct) {}

    public record Revenue(Long thisMonthCents, Long lastMonthCents, Long projectedCents, boolean available) {}

    public record Founder(Long used, Long total) {}

    public record TenantRow(UUID tenantId, String slug, String businessName, String status, UUID subscriptionId,
                            String planCode, String subscriptionStatus, boolean isFounder, Instant graceUntil,
                            Instant createdAt) {}

    public record EmailHealth(long sent24h, long failed24h, long sent7d, long failed7d, Instant lastSentAt,
                              boolean smtpConfigured) {}

    public record PlatformEventDto(UUID id, Instant happenedAt, String tenantName, String eventType, String severity,
                                   String message, Instant resolvedAt) {}
}
