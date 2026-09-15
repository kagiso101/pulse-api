package pulse_api.security;

import java.util.Optional;

/** Verifies a Google-signed ID token (sign-in and Cloud Scheduler OIDC). Mockable in tests. */
public interface IdTokenVerifier {

    record VerifiedIdentity(String email, boolean emailVerified, String subject, String issuer) {}

    /** Empty when the signature, expiry or audience check fails. Never throws for a bad token. */
    Optional<VerifiedIdentity> verify(String idToken, String expectedAudience);
}
