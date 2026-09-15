package pulse_api.dto;

import pulse_api.entity.Enums;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contract §2.2 {@code Overview}. */
public record OverviewDto(
        String range,
        Headline headline,
        List<ProjectCard> projects,
        List<NeedsYouItem> needsYou,
        DailySummaryDto summary,
        Instant lastSnapshotAt
) {
    public record Headline(
            Long visitors,
            Long bookingsThisWeek,
            Long depositsCents,
            Long founderSeatsUsed,
            Long founderSeatsTotal,
            Long cvDownloads,
            int sitesUp,
            int sitesTotal
    ) {}

    public record ProjectCard(
            UUID projectId,
            String slug,
            String name,
            Enums.ProjectKind kind,
            String color,
            List<CardNumber> numbers,
            List<Long> sparkline,
            Enums.UpState status,
            long openAlerts
    ) {}

    public record CardNumber(String key, String label, Long value, String unit) {}

    public record NeedsYouItem(String type, UUID id, String title, String detail, Instant since, UUID projectId, String href) {}
}
