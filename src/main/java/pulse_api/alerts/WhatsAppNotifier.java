package pulse_api.alerts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Meta WhatsApp Cloud API text message to Kagiso's own number only (spec §4.8). */
@Slf4j
@Component
public class WhatsAppNotifier {

    private static final String GRAPH = "https://graph.facebook.com/v21.0";

    private final RestClient http;
    private final String token;
    private final String phoneNumberId;
    private final String to;

    public WhatsAppNotifier(RestClient http,
                            @Value("${app.whatsapp.token:}") String token,
                            @Value("${app.whatsapp.phone-number-id:}") String phoneNumberId,
                            @Value("${app.whatsapp.to:}") String to) {
        this.http = http;
        this.token = token == null ? "" : token.trim();
        this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.trim();
        this.to = to == null ? "" : to.trim();
    }

    public boolean configured() {
        return !token.isBlank() && !phoneNumberId.isBlank() && !to.isBlank();
    }

    /** Never throws. */
    public boolean send(String text) {
        if (!configured()) {
            return false;
        }
        try {
            http.post().uri(GRAPH + "/{id}/messages", phoneNumberId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "messaging_product", "whatsapp",
                            "to", to,
                            "type", "text",
                            "text", Map.of("body", text.length() > 4000 ? text.substring(0, 4000) : text)))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("WhatsApp notification failed: {}", e.toString());
            return false;
        }
    }
}
