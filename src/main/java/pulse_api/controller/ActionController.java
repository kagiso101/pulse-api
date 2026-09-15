package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.ActionDtos.ActionLogDto;
import pulse_api.dto.ActionDtos.ActionRequest;
import pulse_api.dto.ActionDtos.ActionResult;
import pulse_api.service.ActionService;

import java.util.List;

/** Contract §2.9 — every POST needs {@code {"confirm": true}}; rate-limited 20/min per IP. */
@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actions;

    @PostMapping("/bookvas/subscriptions/{subscriptionId}/extend-grace")
    public ActionResult extendGrace(@PathVariable String subscriptionId, @RequestBody(required = false) ActionRequest body) {
        return actions.extendGrace(subscriptionId, body);
    }

    @PostMapping("/bookvas/subscriptions/{subscriptionId}/comp-period")
    public ActionResult compPeriod(@PathVariable String subscriptionId, @RequestBody(required = false) ActionRequest body) {
        return actions.compPeriod(subscriptionId, body);
    }

    @PostMapping("/bookvas/tenants/{tenantId}/toggle-founder")
    public ActionResult toggleFounder(@PathVariable String tenantId, @RequestBody(required = false) ActionRequest body) {
        return actions.toggleFounder(tenantId, body);
    }

    @PostMapping("/cloud-run/{service}/restart")
    public ActionResult restart(@PathVariable String service, @RequestBody(required = false) ActionRequest body) {
        return actions.restartCloudRun(service, body);
    }

    @PostMapping("/netlify/{siteId}/redeploy")
    public ActionResult redeploy(@PathVariable String siteId, @RequestBody(required = false) ActionRequest body) {
        return actions.redeployNetlify(siteId, body);
    }

    @GetMapping("/log")
    public List<ActionLogDto> log(@RequestParam(defaultValue = "50") int limit) {
        return actions.log(limit);
    }
}
