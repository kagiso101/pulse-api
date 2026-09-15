package pulse_api.dto;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

    private AuthDtos() {}

    public record GoogleAuthRequest(@NotBlank String idToken) {}

    public record AuthResponse(String token, long expiresIn, String email) {}

    public record MeResponse(String email) {}
}
