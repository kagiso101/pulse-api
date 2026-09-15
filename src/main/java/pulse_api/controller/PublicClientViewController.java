package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.ClientViewDto;
import pulse_api.service.ClientViewService;

/** Contract §3 — no auth, rate-limited 60/min per IP, 404 for any unknown token. */
@RestController
@RequiredArgsConstructor
public class PublicClientViewController {

    private final ClientViewService clientViews;

    @GetMapping("/api/public/client-view/{token}")
    public ClientViewDto view(@PathVariable String token) {
        return clientViews.view(token);
    }
}
