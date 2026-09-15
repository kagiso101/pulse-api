package pulse_api.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GithubSignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsGithubsOwnDocumentedExample() {
        // https://docs.github.com/webhooks/using-webhooks/validating-webhook-deliveries
        GithubSignatureVerifier v = new GithubSignatureVerifier(SECRET);
        assertThat(v.verify(BODY, "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17")).isTrue();
    }

    @Test
    void acceptsComputedSignatureCaseInsensitively() throws Exception {
        GithubSignatureVerifier v = new GithubSignatureVerifier(SECRET);
        assertThat(v.verify(BODY, "SHA256=" + hmac(SECRET, BODY).toUpperCase())).isTrue();
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndMalformedHeaders() throws Exception {
        GithubSignatureVerifier v = new GithubSignatureVerifier(SECRET);
        String good = "sha256=" + hmac(SECRET, BODY);
        assertThat(v.verify("Hello, World?".getBytes(StandardCharsets.UTF_8), good)).isFalse();
        assertThat(v.verify(BODY, "sha256=" + hmac("other", BODY))).isFalse();
        assertThat(v.verify(BODY, "sha1=abcd")).isFalse();
        assertThat(v.verify(BODY, "sha256=zz")).isFalse();
        assertThat(v.verify(BODY, null)).isFalse();
    }

    @Test
    void unconfiguredVerifierRejectsEverything() throws Exception {
        GithubSignatureVerifier v = new GithubSignatureVerifier("");
        assertThat(v.configured()).isFalse();
        assertThat(v.verify(BODY, "sha256=" + hmac(SECRET, BODY))).isFalse();
        assertThat(new GithubSignatureVerifier(null).verify(BODY, "sha256=" + hmac(SECRET, BODY))).isFalse();
    }

    private static String hmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
