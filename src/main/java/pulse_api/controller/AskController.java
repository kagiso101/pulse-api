package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pulse_api.ask.AskService;
import pulse_api.dto.AskRequest;

/** Contract §2.10 — text/event-stream of delta / done / error events. Rate-limited 10/min per IP. */
@RestController
@RequiredArgsConstructor
public class AskController {

    private final AskService ask;

    @PostMapping(value = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@Valid @RequestBody AskRequest body) {
        return ask.ask(body);
    }
}
