package pulse_api.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pulse_api.alerts.AlertEngine;
import pulse_api.connector.BillingConnector;
import pulse_api.connector.Connector;
import pulse_api.connector.FetchResult;
import pulse_api.connector.FetchWindow;
import pulse_api.connector.GithubPollConnector;
import pulse_api.connector.UptimeConnector;
import pulse_api.entity.Enums.AlertKind;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.service.RangeResolver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Cloud Scheduler entry points (contract §4). One connector failing never stops the others: each
 * is wrapped, its error recorded in the result, and unconfigured ones are listed as skipped.
 */
@Slf4j
@Service
public class JobRunner {

    public static final Set<String> JOBS = Set.of("metrics", "uptime", "billing", "summary", "ga4-discovery", "alerts", "github-poll");

    private final List<Connector> connectors;
    private final AlertEngine alerts;
    private final Ga4DiscoveryService discovery;
    private final DailySummaryService summary;
    private final RangeResolver ranges;

    public JobRunner(List<Connector> connectors, AlertEngine alerts, Ga4DiscoveryService discovery,
                     DailySummaryService summary, RangeResolver ranges) {
        this.connectors = connectors;
        this.alerts = alerts;
        this.discovery = discovery;
        this.summary = summary;
        this.ranges = ranges;
    }

    public JobResult run(String job) {
        if (!JOBS.contains(job)) {
            throw new ResourceNotFoundException("Unknown job: " + job);
        }
        Instant started = Instant.now();
        log.info("Job {} started", job);
        FetchResult result = switch (job) {
            case "metrics" -> runConnectors(connectors.stream().filter(Connector::partOfMetricsJob).toList())
                    .plus(evaluate(EnumSet.allOf(AlertKind.class)));
            case "uptime" -> runConnectors(bySource(UptimeConnector.SOURCE)).plus(evaluate(EnumSet.of(AlertKind.site_down)));
            case "billing" -> runConnectors(bySource(BillingConnector.SOURCE));
            case "github-poll" -> runConnectors(bySource(GithubPollConnector.SOURCE));
            case "ga4-discovery" -> discovery.run();
            case "summary" -> summary.run();
            case "alerts" -> evaluate(EnumSet.allOf(AlertKind.class));
            default -> throw new ResourceNotFoundException("Unknown job: " + job);
        };
        Instant finished = Instant.now();
        log.info("Job {} finished in {} ms: {} rows, {} notes", job, finished.toEpochMilli() - started.toEpochMilli(),
                result.snapshotsWritten(), result.errors().size());
        return new JobResult(job, started, finished, result.snapshotsWritten(), result.errors());
    }

    private FetchResult runConnectors(List<Connector> selected) {
        LocalDate today = ranges.today();
        FetchWindow window = new FetchWindow(today.minusDays(1), today, Instant.now());
        FetchResult total = FetchResult.of(0);
        for (Connector c : selected) {
            FetchResult r;
            if (!c.configured()) {
                r = FetchResult.skipped(c.source());
            } else {
                try {
                    r = c.fetch(window);
                } catch (Exception e) {
                    log.warn("Connector {} failed: {}", c.source(), e.toString());
                    r = FetchResult.failed(c.source(), e);
                }
            }
            total = total.plus(r);
        }
        return total;
    }

    /** Alert events written count as rows; failures are listed, never thrown. */
    private FetchResult evaluate(Set<AlertKind> kinds) {
        try {
            return FetchResult.of(alerts.evaluate(kinds));
        } catch (Exception e) {
            log.warn("Alert evaluation failed: {}", e.toString());
            return FetchResult.failed("alerts", e);
        }
    }

    private List<Connector> bySource(String source) {
        List<Connector> out = new ArrayList<>();
        for (Connector c : connectors) {
            if (c.source().equals(source)) {
                out.add(c);
            }
        }
        return out;
    }
}
