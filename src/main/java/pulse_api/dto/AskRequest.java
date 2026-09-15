package pulse_api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract §2.10. */
public record AskRequest(@NotBlank @Size(max = 2000) String question, String projectSlug, String range) {}
