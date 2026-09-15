package pulse_api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pulse_api.connector.BookvasConnector;
import pulse_api.connector.CloudRunConnector;
import pulse_api.connector.NetlifyConnector;
import pulse_api.dto.ActionDtos.ActionLogDto;
import pulse_api.dto.ActionDtos.ActionRequest;
import pulse_api.dto.ActionDtos.ActionResult;
import pulse_api.entity.ActionLog;
import pulse_api.exception.ApiException;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.repository.ActionLogRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.security.CurrentUser;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Contract §2.9: every action needs {@code confirm:true}, is logged BEFORE the upstream call and
 * updated after. A failed upstream call is returned as {@code result:"failed"} — never a 500.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionService {

    private final ActionLogRepository actionLogs;
    private final ProjectRepository projects;
    private final BookvasConnector bookvas;
    private final CloudRunConnector cloudRun;
    private final NetlifyConnector netlify;
    private final CurrentUser currentUser;

    public ActionResult extendGrace(String subscriptionId, ActionRequest body) {
        int days = days(body, 5);
        return execute("bookvas.extend-grace", "subscription:" + subscriptionId, Map.of("days", days),
                () -> bookvas.extendGrace(subscriptionId, days));
    }

    public ActionResult compPeriod(String subscriptionId, ActionRequest body) {
        int days = days(body, 30);
        return execute("bookvas.comp-period", "subscription:" + subscriptionId, Map.of("days", days),
                () -> bookvas.compPeriod(subscriptionId, days));
    }

    public ActionResult toggleFounder(String tenantId, ActionRequest body) {
        requireConfirm(body);
        return execute("bookvas.toggle-founder", "tenant:" + tenantId, Map.of(), () -> bookvas.toggleFounder(tenantId));
    }

    public ActionResult restartCloudRun(String service, ActionRequest body) {
        requireConfirm(body);
        projects.findFirstByCloudRunServiceAndActiveTrue(service)
                .orElseThrow(() -> new ResourceNotFoundException("No registry project has cloudRunService '" + service + "'"));
        return execute("cloud-run.restart", "service:" + service, Map.of(), () -> cloudRun.restart(service));
    }

    public ActionResult redeployNetlify(String siteId, ActionRequest body) {
        requireConfirm(body);
        projects.findFirstByNetlifySiteIdAndActiveTrue(siteId)
                .orElseThrow(() -> new ResourceNotFoundException("No registry project has netlifySiteId '" + siteId + "'"));
        return execute("netlify.redeploy", "site:" + siteId, Map.of(), () -> netlify.triggerBuild(siteId));
    }

    public List<ActionLogDto> log(int limit) {
        return actionLogs.findAllByOrderByAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream()
                .map(ActionLogDto::from).toList();
    }

    private ActionResult execute(String action, String target, Map<String, Object> payload, Supplier<String> call) {
        ActionLog entry = new ActionLog();
        entry.setActorEmail(currentUser.email());
        entry.setAction(action);
        entry.setTarget(target);
        entry.setPayload(new HashMap<>(payload));
        entry.setResult("pending");
        entry.setAt(Instant.now());
        entry = actionLogs.save(entry);
        String result;
        String message;
        try {
            message = call.get();
            result = "ok";
        } catch (ApiException e) {
            // not configured (503) is a caller error, not an upstream failure — surface it as such
            if (e.status().value() == 503) {
                finish(entry, "failed", e.getMessage());
                throw e;
            }
            result = "failed";
            message = e.getMessage();
        } catch (Exception e) {
            result = "failed";
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        finish(entry, result, message);
        log.info("AUDIT action={} target={} result={} actor={}", action, target, result, entry.getActorEmail());
        return new ActionResult(result, message, entry.getId());
    }

    private void finish(ActionLog entry, String result, String message) {
        entry.setResult(result);
        entry.setMessage(message == null || message.length() <= 1000 ? message : message.substring(0, 1000));
        entry.setFinishedAt(Instant.now());
        actionLogs.save(entry);
    }

    private static void requireConfirm(ActionRequest body) {
        if (body == null || !Boolean.TRUE.equals(body.confirm())) {
            throw new IllegalArgumentException("Action requires { \"confirm\": true }");
        }
    }

    private static int days(ActionRequest body, int defaultDays) {
        requireConfirm(body);
        int days = body.days() == null ? defaultDays : body.days();
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException("days must be between 1 and 365");
        }
        return days;
    }
}
