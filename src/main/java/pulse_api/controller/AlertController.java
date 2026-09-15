package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.AlertDtos.AlertEventDto;
import pulse_api.dto.AlertDtos.AlertRuleDto;
import pulse_api.dto.AlertDtos.AlertRuleUpdate;
import pulse_api.service.AlertService;

import java.util.List;
import java.util.UUID;

/** Contract §2.4. */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alerts;

    @GetMapping("/rules")
    public List<AlertRuleDto> rules() {
        return alerts.rules();
    }

    @PutMapping("/rules/{id}")
    public AlertRuleDto updateRule(@PathVariable UUID id, @Valid @RequestBody AlertRuleUpdate body) {
        return alerts.updateRule(id, body);
    }

    @GetMapping("/events")
    public List<AlertEventDto> events(@RequestParam(required = false) Boolean open,
                                      @RequestParam(defaultValue = "50") int limit) {
        return alerts.events(open, limit);
    }

    @PostMapping("/events/{id}/ack")
    public AlertEventDto ack(@PathVariable UUID id) {
        return alerts.acknowledge(id);
    }
}
