package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.entity.UptimeCheck;
import pulse_api.repository.UptimeCheckRepository;

import java.time.Instant;
import java.util.List;

/** "Is it up?" from the uptime_check table: down = 3 consecutive failures (spec §4.3). */
@Service
@RequiredArgsConstructor
public class UptimeStatusService {

    public static final int CONSECUTIVE_FAILURES_FOR_DOWN = 3;

    private final UptimeCheckRepository checks;

    /** The target that represents a project: the site when it has one, else its API. */
    public Enums.UptimeTarget primaryTarget(Project project) {
        if (project.getSiteUrl() != null && !project.getSiteUrl().isBlank()) {
            return Enums.UptimeTarget.site;
        }
        if (project.getApiHealthUrl() != null && !project.getApiHealthUrl().isBlank()) {
            return Enums.UptimeTarget.api;
        }
        return null;
    }

    public Enums.UpState status(Project project) {
        Enums.UptimeTarget target = primaryTarget(project);
        return target == null ? Enums.UpState.unknown : status(project, target);
    }

    public Enums.UpState status(Project project, Enums.UptimeTarget target) {
        List<UptimeCheck> recent = checks.findTop3ByProjectIdAndTargetOrderByCheckedAtDesc(project.getId(), target);
        if (recent.isEmpty()) {
            return Enums.UpState.unknown;
        }
        if (isDown(recent)) {
            return Enums.UpState.down;
        }
        return Enums.UpState.up;
    }

    public static boolean isDown(List<UptimeCheck> newestFirst) {
        return newestFirst.size() >= CONSECUTIVE_FAILURES_FOR_DOWN
                && newestFirst.stream().limit(CONSECUTIVE_FAILURES_FOR_DOWN).noneMatch(UptimeCheck::isOk);
    }

    /** Percentage of successful checks since {@code from}, null when there were none. */
    public Double uptimePct(Project project, Instant from) {
        Enums.UptimeTarget target = primaryTarget(project);
        if (target == null) {
            return null;
        }
        long total = checks.countByProjectIdAndTargetAndCheckedAtAfter(project.getId(), target, from);
        if (total == 0) {
            return null;
        }
        long ok = checks.countByProjectIdAndTargetAndOkTrueAndCheckedAtAfter(project.getId(), target, from);
        return Math.round(ok * 10000.0 / total) / 100.0;
    }

    public Integer latestLatencyMs(Project project) {
        Enums.UptimeTarget target = primaryTarget(project);
        if (target == null) {
            return null;
        }
        return checks.findFirstByProjectIdAndTargetOrderByCheckedAtDesc(project.getId(), target)
                .map(UptimeCheck::getLatencyMs).orElse(null);
    }
}
