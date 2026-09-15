package pulse_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pulse_api.connector.BookvasConnector;
import pulse_api.connector.GithubPollConnector;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.repository.ProjectRepository;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns GitHub {@code push} and {@code deployment_status} payloads into deploy_event rows,
 * matching {@code repository.full_name} against {@code project.github_repos} (contract §4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubWebhookService {

    private final ObjectMapper json;
    private final ProjectRepository projects;
    private final DeployService deploys;

    /** Returns the number of deploy_event rows written (0 for ignored events). */
    public int handle(String event, byte[] body) {
        JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook body is not JSON");
        }
        String repo = payload.path("repository").path("full_name").asText(null);
        if (repo == null) {
            return 0;
        }
        Optional<Project> project = projectFor(repo);
        String kind = event == null ? "" : event.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "push" -> push(repo, project, payload);
            case "deployment_status" -> deploymentStatus(repo, project, payload);
            default -> 0;
        };
    }

    private int push(String repo, Optional<Project> project, JsonNode p) {
        String sha = p.path("after").asText(null);
        if (sha == null || sha.matches("0+")) {
            return 0; // branch deletion
        }
        String ref = p.path("ref").asText("");
        String branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        JsonNode head = p.path("head_commit");
        Instant at = BookvasConnector.instant(head.path("timestamp"));
        return deploys.upsert(project.map(Project::getId).orElse(null), repo, sha, branch,
                GithubPollConnector.firstLine(head.path("message").asText(null)), null, "pushed",
                at == null ? Instant.now() : at, Enums.DeploySource.github);
    }

    private int deploymentStatus(String repo, Optional<Project> project, JsonNode p) {
        JsonNode deployment = p.path("deployment");
        String sha = deployment.path("sha").asText(null);
        String state = p.path("deployment_status").path("state").asText(null);
        Instant at = BookvasConnector.instant(p.path("deployment_status").path("updated_at"));
        return deploys.upsert(project.map(Project::getId).orElse(null), repo, sha, deployment.path("ref").asText(null),
                "deployment " + state, deployment.path("environment").asText(null), state,
                at == null ? Instant.now() : at, Enums.DeploySource.github);
    }

    private Optional<Project> projectFor(String repo) {
        return projects.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .filter(p -> p.getGithubRepos() != null && p.getGithubRepos().stream().anyMatch(r -> r.equalsIgnoreCase(repo)))
                .findFirst();
    }
}
