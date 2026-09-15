package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pulse_api.dto.AlertDtos.AlertEventDto;
import pulse_api.dto.AlertDtos.AlertRuleDto;
import pulse_api.dto.AlertDtos.AlertRuleUpdate;
import pulse_api.entity.AlertEvent;
import pulse_api.entity.AlertRule;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.AlertRuleRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contract §2.4 — rule editing and the event list; firing lives in {@code alerts.AlertEngine}. */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;

    public List<AlertRuleDto> rules() {
        return rules.findAllByOrderByCreatedAtAsc().stream().map(AlertRuleDto::from).toList();
    }

    public AlertRuleDto updateRule(UUID id, AlertRuleUpdate body) {
        AlertRule rule = rules.findById(id).orElseThrow(() -> new ResourceNotFoundException("Alert rule not found"));
        rule.setEnabled(body.enabled());
        rule.setThreshold(body.threshold());
        rule.setChannel(body.channel());
        return AlertRuleDto.from(rules.save(rule));
    }

    public List<AlertEventDto> events(Boolean open, int limit) {
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        List<AlertEvent> rows = open == null ? events.findAllByOrderByFiredAtDesc(page)
                : open ? events.findByAcknowledgedAtIsNullOrderByFiredAtDesc(page)
                : events.findByAcknowledgedAtIsNotNullOrderByFiredAtDesc(page);
        return rows.stream().map(AlertEventDto::from).toList();
    }

    public AlertEventDto acknowledge(UUID id) {
        AlertEvent event = events.findById(id).orElseThrow(() -> new ResourceNotFoundException("Alert event not found"));
        if (event.getAcknowledgedAt() == null) {
            event.setAcknowledgedAt(Instant.now());
            event = events.save(event);
        }
        return AlertEventDto.from(event);
    }
}
