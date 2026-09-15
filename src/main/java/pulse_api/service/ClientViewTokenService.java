package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pulse_api.entity.Project;
import pulse_api.repository.ProjectRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** Client view tokens (spec §7): 32 random bytes, base64url on the wire, SHA-256 hex at rest. */
@Service
@RequiredArgsConstructor
public class ClientViewTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectRepository projects;

    public String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Empty for an unknown, revoked or malformed token — the caller answers 404, never 403. */
    public Optional<Project> lookup(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return Optional.empty();
        }
        return projects.findByClientViewTokenHash(hash(rawToken));
    }
}
