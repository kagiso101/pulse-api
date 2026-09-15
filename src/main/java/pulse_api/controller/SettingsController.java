package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.SettingsDtos.SettingsDto;
import pulse_api.dto.SettingsDtos.SettingsUpdate;
import pulse_api.service.SettingsService;

/** Contract §2.11. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping
    public SettingsDto get() {
        return settings.get();
    }

    @PutMapping
    public SettingsDto update(@Valid @RequestBody SettingsUpdate body) {
        return settings.update(body.notificationChannel());
    }
}
