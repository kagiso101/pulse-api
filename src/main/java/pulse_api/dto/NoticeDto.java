package pulse_api.dto;

import pulse_api.entity.Enums;
import pulse_api.entity.Notice;

import java.time.Instant;
import java.util.UUID;

/** Contract §2.12. */
public record NoticeDto(UUID id, Enums.NoticeKind kind, String title, String body, Instant createdAt, Instant readAt,
                        String href) {
    public static NoticeDto from(Notice n) {
        return new NoticeDto(n.getId(), n.getKind(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.getReadAt(),
                n.getHref());
    }
}
