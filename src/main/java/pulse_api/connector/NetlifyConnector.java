package pulse_api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.exception.ApiException;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.DeployService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Netlify deploys per site (spec §4.4) → deploy_event, plus {@link #triggerBuild} for the redeploy action. */
@Slf4j
@Component
public class NetlifyConnector implements Connector {

    public static final String SOURCE = "netlify";
    private static final String API = "https://api.netlify.com/api/v1";

    private final RestClient http;
    private final ObjectMapper json;
    private final ProjectRepository projects;
    private final DeployService deploys;
    private final String token;

    public NetlifyConnector(RestClient http, ObjectMapper upstreamJson, ProjectRepository projects, DeployService deploys,
                            @Value("${app.netlify.token:}") String token) {
        this.http = http;
        this.json = upstreamJson;
        this.projects = projects;
        this.deploys = deploys;
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean configured() {
        return !token.isBlank();
    }

    @Override
    public FetchResult fetch(FetchWindow window) {
        int written = 0;
        List<String> errors = new ArrayList<>();
        for (Project p : projects.findByActiveTrueOrderBySortOrderAscNameAsc()) {
            if (p.getNetlifySiteId() == null || p.getNetlifySiteId().isBlank()) continue;
            try {
                JsonNode list = json.readTree(http.get()
                        .uri(API + "/sites/{site}/deploys?per_page=10", p.getNetlifySiteId())
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve().body(String.class));
                for (JsonNode d : list) {
                    String sha = d.path("commit_ref").asText(null);
                    if (sha == null || sha.isBlank()) {
                        sha = "netlify-" + d.path("id").asText();
                    }
                    Instant at = BookvasConnector.instant(d.path("published_at"));
                    if (at == null) at = BookvasConnector.instant(d.path("created_at"));
                    written += deploys.upsert(p.getId(), firstRepo(p), sha, d.path("branch").asText(null),
                            d.path("title").asText(null), d.path("context").asText(null), d.path("state").asText(null),
                            at, Enums.DeploySource.netlify);
                }
            } catch (Exception e) {
                log.warn("Netlify pull failed for {}: {}", p.getSlug(), e.toString());
                errors.add(source() + " (" + p.getSlug() + "): " + FetchResult.describe(e));
            }
        }
        return FetchResult.of(written, errors);
    }

    /** POST /sites/{id}/builds — Netlify rebuilds the production branch. Returns the build id. */
    public String triggerBuild(String siteId) {
        if (!configured()) {
            throw ApiException.notConfigured("Netlify connector");
        }
        try {
            JsonNode build = json.readTree(http.post()
                    .uri(API + "/sites/{site}/builds", siteId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve().body(String.class));
            return "Netlify build " + build.path("id").asText("?") + " queued for site " + siteId;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.upstream("Netlify build trigger failed: " + FetchResult.describe(e));
        }
    }

    private static String firstRepo(Project p) {
        return p.getGithubRepos() == null || p.getGithubRepos().isEmpty() ? null : p.getGithubRepos().get(0);
    }
}
