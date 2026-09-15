package pulse_api.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> JwtService.validateSecret(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtService.validateSecret("  ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortSecretWithoutEchoingIt() {
        assertThatThrownBy(() -> JwtService.validateSecret("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("too-short"));
    }

    @Test
    void acceptsStrongSecretAndRoundTrips() {
        JwtService.validateSecret(SECRET);
        JwtService jwt = new JwtService(SECRET, 43200);
        String token = jwt.generateToken("owner@example.com");
        Claims claims = jwt.parse(token);
        assertThat(claims.getSubject()).isEqualTo("owner@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("OWNER");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(43200 * 1000L);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        String token = new JwtService(SECRET, 3600).generateToken("owner@example.com");
        JwtService other = new JwtService("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 3600);
        assertThatThrownBy(() -> other.parse(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
}
