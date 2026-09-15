package pulse_api.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional in-process timers mirroring the Cloud Scheduler cadence (spec §1). Off by default:
 * Cloud Run scales to zero, so production relies on Cloud Scheduler hitting /internal/jobs/*.
 * Enable locally with PULSE_INPROCESS_SCHEDULING=true.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.inprocess-scheduling", havingValue = "true")
public class InProcessScheduler {

    private final JobRunner jobs;

    @Scheduled(cron = "0 */15 * * * *", zone = "Africa/Johannesburg")
    public void metrics() {
        run("metrics");
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "Africa/Johannesburg")
    public void uptime() {
        run("uptime");
    }

    @Scheduled(cron = "0 7 * * * *", zone = "Africa/Johannesburg")
    public void billing() {
        run("billing");
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Africa/Johannesburg")
    public void summary() {
        run("summary");
    }

    @Scheduled(cron = "0 23 * * * *", zone = "Africa/Johannesburg")
    public void ga4Discovery() {
        run("ga4-discovery");
    }

    @Scheduled(cron = "0 41 * * * *", zone = "Africa/Johannesburg")
    public void githubPoll() {
        run("github-poll");
    }

    private void run(String job) {
        try {
            jobs.run(job);
        } catch (Exception e) {
            log.warn("In-process job {} failed: {}", job, e.toString());
        }
    }
}
