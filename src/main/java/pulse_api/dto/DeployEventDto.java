package pulse_api.dto;

import pulse_api.entity.DeployEvent;
import pulse_api.entity.Enums;

import java.time.Instant;
import java.util.UUID;

/** Contract §2.7 {@code DeployEvent}. */
public record DeployEventDto(
        UUID id,
        UUID projectId,
        String repo,
        String sha,
        String branch,
        String message,
        String environment,
        Instant deployedAt,
        Enums.DeploySource source
) {
    public static DeployEventDto from(DeployEvent d) {
        return new DeployEventDto(d.getId(), d.getProjectId(), d.getRepo(), d.getSha(), d.getBranch(),
                d.getMessage(), d.getEnvironment(), d.getDeployedAt(), d.getSource());
    }
}
