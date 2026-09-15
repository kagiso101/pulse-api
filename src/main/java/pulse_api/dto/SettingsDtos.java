package pulse_api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Contract §2.11. */
public final class SettingsDtos {

    private SettingsDtos() {}

    public record SettingsDto(String allowedEmailMasked, String notificationChannel, boolean whatsappConfigured,
                              boolean emailConfigured, boolean ga4Configured, boolean bookvasConfigured,
                              boolean netlifyConfigured, boolean cloudRunConfigured, boolean billingConfigured,
                              boolean anthropicConfigured, String timezone) {}

    public record SettingsUpdate(@NotBlank @Pattern(regexp = "whatsapp|email") String notificationChannel) {}
}
