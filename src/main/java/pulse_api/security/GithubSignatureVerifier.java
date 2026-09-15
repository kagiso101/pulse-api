package pulse_api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Validates GitHub's {@code X-Hub-Signature-256: sha256=<hex hmac>} header (spec §7). */
@Component
public class GithubSignatureVerifier {

    private final String secret;

    public GithubSignatureVerifier(@Value("${app.github.webhook-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public boolean configured() {
        return !secret.isBlank();
    }

    /** Constant-time comparison; false when unconfigured, header missing or malformed. */
    public boolean verify(byte[] body, String signatureHeader) {
        if (!configured() || body == null || signatureHeader == null) {
            return false;
        }
        String header = signatureHeader.trim().toLowerCase(Locale.ROOT);
        if (!header.startsWith("sha256=")) {
            return false;
        }
        byte[] expected = hmac(body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(header.substring("sha256=".length()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
