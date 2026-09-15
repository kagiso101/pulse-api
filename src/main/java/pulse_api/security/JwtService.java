package pulse_api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/** Issues and parses the Pulse JWT: HS256, 12h, claims sub=email and role=OWNER (contract §1). */
@Service
public class JwtService {

    public static final String ROLE_OWNER = "OWNER";
    private static final int MIN_SECRET_BYTES = 32;

    private final String secret;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-seconds:43200}") long ttlSeconds) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    /** Fail fast: the app must never boot with a missing or weak signing key. */
    @PostConstruct
    void validateSecretOnStartup() {
        validateSecret(secret);
    }

    static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing key is not configured. Set the PULSE_JWT_SECRET environment variable.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // never log or echo the value itself
            throw new IllegalStateException("PULSE_JWT_SECRET is too short: HS256 requires a signing key of at least "
                    + MIN_SECRET_BYTES + " bytes (256 bits).");
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String generateToken(String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(Map.of("role", ROLE_OWNER))
                .setSubject(email)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlSeconds * 1000))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Parses and verifies signature + expiry; throws {@link io.jsonwebtoken.JwtException} otherwise. */
    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(signingKey()).build().parseClaimsJws(token).getBody();
    }

    private Key signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
