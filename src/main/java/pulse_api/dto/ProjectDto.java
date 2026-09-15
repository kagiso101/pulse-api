package pulse_api.dto;

import pulse_api.entity.Enums;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contract §2.1 {@code Project}. */
public record ProjectDto(
        UUID id,
        String slug,
        String name,
        Enums.ProjectKind kind,
        String ga4PropertyId,
        String siteUrl,
        String apiHealthUrl,
        String netlifySiteId,
        String cloudRunService,
        List<String> githubRepos,
        String color,
        int sortOrder,
        boolean active,
        boolean autoDiscovered,
        Instant discoveredAt,
        boolean hasClientViewToken,
        Status status
) {
    public record Status(Enums.UpState up, long openAlerts, Instant lastSnapshotAt) {}
}
