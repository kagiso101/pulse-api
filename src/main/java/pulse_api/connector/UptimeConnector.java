package pulse_api.connector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.entity.UptimeCheck;
import pulse_api.repository.ProjectRepository;
import pulse_api.repository.UptimeCheckRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP GET to every active project's site_url and api_health_url (spec §4.3): 5s timeout, redirects
 * followed, status + latency recorded. "Down" is derived from three consecutive failures by
 * {@code UptimeStatusService} / the alert engine, not here. Always configured — no secrets needed.
 */
@Slf4j
@Component
public class UptimeConnector implements Connector {

    public static final String SOURCE = "uptime";

    private final ProjectRepository projects;
    private final UptimeCheckRepository checks;
    private final HttpClient http;
    private final Duration timeout;

    public UptimeConnector(ProjectRepository projects, UptimeCheckRepository checks,
                           @Value("${app.uptime.timeout-ms:5000}") long timeoutMs) {
        this.projects = projects;
        this.checks = checks;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public boolean partOfMetricsJob() {
        return false;
    }

    @Override
    public FetchResult fetch(FetchWindow window) {
        int written = 0;
        List<String> errors = new ArrayList<>();
        for (Project p : projects.findByActiveTrueOrderBySortOrderAscNameAsc()) {
            written += probe(p, Enums.UptimeTarget.site, p.getSiteUrl(), errors);
            written += probe(p, Enums.UptimeTarget.api, p.getApiHealthUrl(), errors);
        }
        return FetchResult.of(written, errors);
    }

    private int probe(Project p, Enums.UptimeTarget target, String url, List<String> errors) {
        if (url == null || url.isBlank()) {
            return 0;
        }
        UptimeCheck check = new UptimeCheck();
        check.setProjectId(p.getId());
        check.setTarget(target);
        check.setCheckedAt(Instant.now());
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(timeout)
                    .header("User-Agent", "pulse-api uptime/1.0")
                    .GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            check.setStatusCode(response.statusCode());
            check.setOk(response.statusCode() >= 200 && response.statusCode() < 400);
            if (!check.isOk()) {
                check.setError("HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            check.setOk(false);
            check.setError(FetchResult.describe(e));
            log.info("Uptime probe failed for {} {}: {}", p.getSlug(), target, check.getError());
        } finally {
            check.setLatencyMs((int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000));
        }
        checks.save(check);
        return 1;
    }
}
