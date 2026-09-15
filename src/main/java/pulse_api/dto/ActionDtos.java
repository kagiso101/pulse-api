package pulse_api.dto;

import pulse_api.entity.ActionLog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Contract §2.9. */
public final class ActionDtos {

    private ActionDtos() {}

    /** Every action body: {@code confirm} must be true; {@code days} only for the subscription actions. */
    public record ActionRequest(Boolean confirm, Integer days) {}

    public record ActionResult(String result, String message, UUID actionLogId) {}

    public record ActionLogDto(UUID id, String actorEmail, String action, String target, Map<String, Object> payload,
                               String result, Instant at) {
        public static ActionLogDto from(ActionLog a) {
            return new ActionLogDto(a.getId(), a.getActorEmail(), a.getAction(), a.getTarget(), a.getPayload(),
                    a.getResult(), a.getAt());
        }
    }
}
