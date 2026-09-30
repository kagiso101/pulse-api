package pulse_api.alerts;

import pulse_api.entity.Enums.AlertKind;

import java.time.Instant;
import java.util.Map;

/**
 * Everything a channel needs to present one alert well: the kind, a severity for colour,
 * the one-line title, the plain-English detail, the structured payload, and where it came
 * from. Channels that can only carry text flatten it; email renders it.
 */
public record AlertMessage(
        AlertKind kind,
        Severity severity,
        String title,
        String detail,
        Map<String, Object> payload,
        String projectName,
        Instant at) {

    public enum Severity { critical, warning, info }

    public static AlertMessage of(AlertKind kind, String title, String detail, Map<String, Object> payload, String projectName) {
        return new AlertMessage(kind, severityOf(kind), title, detail == null ? "" : detail,
                payload == null ? Map.of() : payload, projectName, Instant.now());
    }

    /** A plain email that is not an alert (the daily summary): neutral colour, no kind. */
    public static AlertMessage plain(String title, String body) {
        return new AlertMessage(null, Severity.info, title, body == null ? "" : body, Map.of(), null, Instant.now());
    }

    static Severity severityOf(AlertKind kind) {
        if (kind == null) {
            return Severity.info;
        }
        return switch (kind) {
            case site_down, payment_provider_degraded -> Severity.critical;
            case email_failures, checkout_stalled, merchant_verification_stalled, signup_rate_limited, tenant_grace -> Severity.warning;
            case deposit_paid, founder_seat_claimed, plan_price_changed, prospect_overdue -> Severity.info;
        };
    }

    /** What WhatsApp and the in-app feed get. */
    public String asText() {
        return title + (detail.isBlank() ? "" : "\n" + detail);
    }
}
