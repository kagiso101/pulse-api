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
import pulse_api.repository.ProjectRepository;
import pulse_api.service.DeployService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Hourly fallback for the GitHub webhook (spec §4.6): the latest commit on each registry repo's default branch. */
@Slf4j
@Component
public class GithubPollConnector implements Connector {

    public static final String SOURCE = "github";
    private static final String API = "https://api.github.com";

    private final RestClient http;
    private final ObjectMapper json;
    private final ProjectRepository projects;
    private final DeployService deploys;
    private final String token;

    public GithubPollConnector(RestClient http, ObjectMapper upstreamJson, ProjectRepository projects, DeployService deploys,
                               @Value("${app.github.token:}") String token) {
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
    public boolean partOfMetricsJob() {
        return false;
    }

    @Override
    public FetchResult fetch(FetchWindow window) {
        int written = 0;
        List<String> errors = new ArrayList<>();
        for (Project p : projects.findByActiveTrueOrderBySortOrderAscNameAsc()) {
            if (p.getGithubRepos() == null) continue;
            for (String repo : p.getGithubRepos()) {
                try {
                    JsonNode repoInfo = get("/repos/" + repo);
                    String branch = repoInfo.path("default_branch").asText("main");
                    JsonNode commits = get("/repos/" + repo + "/commits?per_page=1&sha=" + branch);
                    if (!commits.isArray() || commits.isEmpty()) continue;
                    JsonNode c = commits.get(0);
                    Instant at = BookvasConnector.instant(c.path("commit").path("committer").path("date"));
                    written += deploys.upsert(p.getId(), repo, c.path("sha").asText(), branch,
                            firstLine(c.path("commit").path("message").asText(null)), null, "pushed", at,
                            Enums.DeploySource.github);
                } catch (Exception e) {
                    log.warn("GitHub poll failed for {}: {}", repo, e.toString());
                    errors.add(source() + " (" + repo + "): " + FetchResult.describe(e));
                }
            }
        }
        return FetchResult.of(written, errors);
    }

    private JsonNode get(String path) throws Exception {
        return json.readTree(http.get().uri(API + path)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "pulse-api")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class));
    }

    public static String firstLine(String message) {
        if (message == null) return null;
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
