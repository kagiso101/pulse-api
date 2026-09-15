package pulse_api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pulse_api.entity.Enums;

import java.util.List;

/** Contract §2.1 {@code ProjectUpsert}. */
public record ProjectUpsert(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}", message = "must be lowercase letters, digits and dashes") String slug,
        @NotBlank @Size(max = 120) String name,
        @NotNull Enums.ProjectKind kind,
        @Size(max = 32) String ga4PropertyId,
        @Size(max = 500) String siteUrl,
        @Size(max = 500) String apiHealthUrl,
        @Size(max = 120) String netlifySiteId,
        @Size(max = 120) String cloudRunService,
        List<@Pattern(regexp = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", message = "must be owner/repo") String> githubRepos,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "must be a #RRGGBB colour") String color,
        Integer sortOrder,
        Boolean active
) {}
