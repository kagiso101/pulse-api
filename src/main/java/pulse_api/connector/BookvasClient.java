package pulse_api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import pulse_api.exception.ApiException;

import java.time.Instant;
import java.util.Map;

/**
 * Thin HTTP client for Bookvas's super-admin API (contract §5): logs in with the Pulse
 * super-admin credential, caches the JWT in memory and re-logs in once on a 401. Pulse only ever
 * talks to Bookvas through these endpoints (hard rule 3).
 */
@Slf4j
@Component
public class BookvasClient {

    private final RestClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String email;
    private final String password;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public BookvasClient(RestClient http, ObjectMapper upstreamJson,
                         @Value("${app.bookvas.base-url:}") String baseUrl,
                         @Value("${app.bookvas.super-email:}") String email,
                         @Value("${app.bookvas.super-password:}") String password) {
        this.http = http;
        this.json = upstreamJson;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !email.isBlank() && !password.isBlank();
    }

    public JsonNode get(String path) {
        return call("GET", path, null);
    }

    public JsonNode post(String path, Object body) {
        return call("POST", path, body == null ? Map.of() : body);
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            return exchange(method, path, body, bearer());
        } catch (HttpClientErrorException.Unauthorized expired) {
            token = null;
            return exchange(method, path, body, bearer());
        }
    }

    private JsonNode exchange(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec spec = http.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(baseUrl + path)
                .header("Authorization", "Bearer " + bearer)
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON);
        }
        String raw = (body == null ? spec : spec.body(body)).retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw ApiException.upstream("Bookvas returned " + res.getStatusCode().value() + " for " + path);
                })
                .body(String.class);
        return parse(raw);
    }

    private synchronized String bearer() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        JsonNode login = parse(http.post().uri(baseUrl + "/api/auth/super/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve().body(String.class));
        if (login == null || !login.hasNonNull("token")) {
            throw ApiException.upstream("Bookvas login did not return a token");
        }
        token = login.get("token").asText();
        long expiresIn = login.path("expiresIn").asLong(23 * 3600);
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        log.info("Bookvas super-admin session refreshed");
        return token;
    }

    private JsonNode parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? json.nullNode() : json.readTree(raw);
        } catch (Exception e) {
            throw ApiException.upstream("Bookvas returned an unreadable response");
        }
    }
}
