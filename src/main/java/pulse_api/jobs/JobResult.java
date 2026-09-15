package pulse_api.jobs;

import java.time.Instant;
import java.util.List;

/** Contract §4 job response. {@code errors} also carries "<source>: skipped (not configured)" lines. */
public record JobResult(String job, Instant startedAt, Instant finishedAt, int snapshotsWritten, List<String> errors) {}
