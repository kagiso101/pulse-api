package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.AuthDtos.AuthResponse;
import pulse_api.dto.AuthDtos.GoogleAuthRequest;
import pulse_api.dto.AuthDtos.MeResponse;
import pulse_api.security.CurrentUser;
import pulse_api.service.AuthService;

/** Contract §1. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class GoogleAuthController {

    private final AuthService auth;
    private final CurrentUser currentUser;

    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleAuthRequest body) {
        return auth.loginWithGoogle(body.idToken());
    }

    @GetMapping("/me")
    public MeResponse me() {
        return new MeResponse(currentUser.email());
    }
}
