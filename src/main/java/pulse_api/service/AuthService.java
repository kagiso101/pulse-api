package pulse_api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import pulse_api.dto.AuthDtos.AuthResponse;
import pulse_api.exception.ApiException;
import pulse_api.security.IdTokenVerifier;
import pulse_api.security.JwtService;

/** Google sign-in → one allow-listed email → Pulse JWT (spec §7, contract §1). */
@Slf4j
@Service
public class AuthService {

    private final IdTokenVerifier verifier;
    private final JwtService jwt;
    private final String allowedEmail;
    private final String googleClientId;

    public AuthService(IdTokenVerifier verifier, JwtService jwt,
                       @Value("${app.auth.allowed-email:}") String allowedEmail,
                       @Value("${app.auth.google-client-id:}") String googleClientId) {
        this.verifier = verifier;
        this.jwt = jwt;
        this.allowedEmail = allowedEmail == null ? "" : allowedEmail.trim();
        this.googleClientId = googleClientId == null ? "" : googleClientId.trim();
    }

    public AuthResponse loginWithGoogle(String idToken) {
        if (allowedEmail.isBlank() || googleClientId.isBlank()) {
            throw ApiException.notConfigured("Google sign-in (PULSE_ALLOWED_EMAIL / GOOGLE_CLIENT_ID)");
        }
        IdTokenVerifier.VerifiedIdentity identity = verifier.verify(idToken, googleClientId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token", "INVALID_TOKEN"));
        if (!identity.emailVerified() || identity.email() == null || !identity.email().equalsIgnoreCase(allowedEmail)) {
            log.warn("AUDIT sign-in rejected: email not allow-listed");
            throw new ApiException(HttpStatus.FORBIDDEN, "This Google account is not allowed to use Pulse", "NOT_ALLOWED");
        }
        String email = identity.email().toLowerCase();
        return new AuthResponse(jwt.generateToken(email), jwt.ttlSeconds(), email);
    }

    public String allowedEmailMasked() {
        if (allowedEmail.isBlank() || !allowedEmail.contains("@")) {
            return "";
        }
        int at = allowedEmail.indexOf('@');
        return allowedEmail.charAt(0) + "***" + allowedEmail.substring(at);
    }
}
