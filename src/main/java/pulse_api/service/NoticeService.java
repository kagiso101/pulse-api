package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pulse_api.dto.NoticeDto;
import pulse_api.entity.Enums;
import pulse_api.entity.Notice;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.repository.NoticeRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository notices;

    public List<NoticeDto> list(boolean unreadOnly) {
        var rows = unreadOnly ? notices.findByReadAtIsNullOrderByCreatedAtDesc()
                : notices.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100));
        return rows.stream().map(NoticeDto::from).toList();
    }

    public void markRead(UUID id) {
        Notice notice = notices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notice not found"));
        if (notice.getReadAt() == null) {
            notice.setReadAt(Instant.now());
            notices.save(notice);
        }
    }

    public Notice create(Enums.NoticeKind kind, String title, String body, String href) {
        Notice notice = new Notice();
        notice.setKind(kind);
        notice.setTitle(title);
        notice.setBody(body == null ? "" : body);
        notice.setHref(href);
        return notices.save(notice);
    }
}
