package pulse_api.dto;

import jakarta.validation.constraints.NotNull;
import pulse_api.entity.AlertEvent;
import pulse_api.entity.AlertRule;
import pulse_api.entity.Enums;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Contract §2.4. */
public final class AlertDtos {

    private AlertDtos() {}

    public record AlertRuleDto(UUID id, UUID projectId, Enums.AlertKind kind, BigDecimal threshold,
                               Enums.Channel channel, boolean enabled, String label) {
        public static AlertRuleDto from(AlertRule r) {
            return new AlertRuleDto(r.getId(), r.getProjectId(), r.getKind(),
                    r.getThreshold() == null ? null : r.getThreshold().stripTrailingZeros(), r.getChannel(),
                    r.isEnabled(), r.getLabel());
        }
    }

    public record AlertRuleUpdate(@NotNull Boolean enabled, BigDecimal threshold, @NotNull Enums.Channel channel) {}

    public record AlertEventDto(UUID id, UUID ruleId, Enums.AlertKind kind, UUID projectId, Instant firedAt,
                                Map<String, Object> payload, boolean delivered, Instant acknowledgedAt,
                                String title, String detail) {
        public static AlertEventDto from(AlertEvent e) {
            return new AlertEventDto(e.getId(), e.getRuleId(), e.getKind(), e.getProjectId(), e.getFiredAt(),
                    e.getPayload(), e.isDelivered(), e.getAcknowledgedAt(), e.getTitle(), e.getDetail());
        }
    }
}
