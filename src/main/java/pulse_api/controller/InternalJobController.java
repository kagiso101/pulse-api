package pulse_api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.jobs.JobResult;
import pulse_api.jobs.JobRunner;

/** Contract §4 — Cloud Scheduler only (see SchedulerAuthFilter). Never called by the UI. */
@RestController
@RequiredArgsConstructor
public class InternalJobController {

    private final JobRunner jobs;

    @PostMapping("/internal/jobs/{job}")
    public JobResult run(@PathVariable String job) {
        return jobs.run(job);
    }
}
