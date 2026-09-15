package pulse_api.security;

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.oauth2.TokenVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** Signature + expiry + audience via Google's published certs; issuer restricted to Google accounts. */
@Slf4j
@Component
public class GoogleIdTokenVerifier implements IdTokenVerifier {

    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    @Override
    public Optional<VerifiedIdentity> verify(String idToken, String expectedAudience) {
        if (idToken == null || idToken.isBlank() || expectedAudience == null || expectedAudience.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonWebSignature jws = TokenVerifier.newBuilder().setAudience(expectedAudience).build().verify(idToken);
            JsonWebToken.Payload payload = jws.getPayload();
            if (!GOOGLE_ISSUERS.contains(String.valueOf(payload.getIssuer()))) {
                log.warn("AUDIT id token rejected: unexpected issuer");
                return Optional.empty();
            }
            Object email = payload.get("email");
            Object verified = payload.get("email_verified");
            return Optional.of(new VerifiedIdentity(
                    email == null ? null : email.toString(),
                    Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified)),
                    payload.getSubject(),
                    payload.getIssuer()));
        } catch (TokenVerifier.VerificationException e) {
            log.warn("AUDIT id token rejected: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("AUDIT id token verification failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
