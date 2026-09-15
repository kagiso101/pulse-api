package pulse_api.connector;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The GA4 service account (Data + Admin APIs) from the {@code GA4_SA_JSON} secret — the whole
 * key file as one string. Parsed once, lazily; never logged.
 */
@Component
public class GoogleCredentialsFactory {

    private static final List<String> GA4_SCOPES = List.of("https://www.googleapis.com/auth/analytics.readonly");

    private final String ga4SaJson;
    private volatile GoogleCredentials ga4;

    public GoogleCredentialsFactory(@Value("${app.ga4.sa-json:}") String ga4SaJson) {
        this.ga4SaJson = ga4SaJson == null ? "" : ga4SaJson.trim();
    }

    public boolean ga4Configured() {
        return !ga4SaJson.isBlank();
    }

    public GoogleCredentials ga4Credentials() throws IOException {
        GoogleCredentials creds = ga4;
        if (creds == null) {
            synchronized (this) {
                if (ga4 == null) {
                    ga4 = ServiceAccountCredentials
                            .fromStream(new ByteArrayInputStream(ga4SaJson.getBytes(StandardCharsets.UTF_8)))
                            .createScoped(GA4_SCOPES);
                }
                creds = ga4;
            }
        }
        return creds;
    }
}
