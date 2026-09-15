package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.NoticeDto;
import pulse_api.service.NoticeService;

import java.util.List;
import java.util.UUID;

/** Contract §2.12. */
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService notices;

    @GetMapping
    public List<NoticeDto> list(@RequestParam(defaultValue = "false") boolean unread) {
        return notices.list(unread);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        notices.markRead(id);
    }
}
