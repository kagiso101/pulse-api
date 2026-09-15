package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.exception.ApiException;
import pulse_api.security.GithubSignatureVerifier;
import pulse_api.service.GithubWebhookService;

import java.util.Map;

/** Contract §4 — HMAC-SHA256 validated push / deployment_status receiver. */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GithubWebhookController {

    private final GithubSignatureVerifier signatures;
    private final GithubWebhookService webhooks;

    @PostMapping("/api/webhooks/github")
    public Map<String, Object> receive(@RequestBody byte[] body,
                                       @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                       @RequestHeader(value = "X-GitHub-Event", required = false) String event) {
        if (!signatures.configured()) {
            throw ApiException.notConfigured("GitHub webhook (GITHUB_WEBHOOK_SECRET)");
        }
        if (!signatures.verify(body, signature)) {
            log.warn("AUDIT github webhook rejected: bad signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature", "BAD_SIGNATURE");
        }
        int written = webhooks.handle(event, body);
        return Map.of("status", "ok", "event", event == null ? "" : event, "deployEvents", written);
    }
}
