package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pulse_api.dto.DeployEventDto;
import pulse_api.entity.Enums;
import pulse_api.repository.DeployEventRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The deploy timeline: reads for the API, one upsert for every connector/webhook. */
@Service
@RequiredArgsConstructor
public class DeployService {

    private static final String UPSERT = """
            INSERT INTO deploy_event (project_id, repo, sha, branch, message, environment, state, deployed_at, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, sha, COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO UPDATE SET branch = COALESCE(EXCLUDED.branch, deploy_event.branch),
                          message = COALESCE(EXCLUDED.message, deploy_event.message),
                          environment = COALESCE(EXCLUDED.environment, deploy_event.environment),
                          state = COALESCE(EXCLUDED.state, deploy_event.state),
                          deployed_at = EXCLUDED.deployed_at
            """;

    private final DeployEventRepository deploys;
    private final JdbcTemplate jdbc;

    public List<DeployEventDto> list(UUID projectId, int limit) {
        var page = PageRequest.of(0, clamp(limit));
        var rows = projectId == null
                ? deploys.findAllByOrderByDeployedAtDesc(page)
                : deploys.findByProjectIdOrderByDeployedAtDesc(projectId, page);
        return rows.stream().map(DeployEventDto::from).toList();
    }

    public DeployEventDto latest(UUID projectId) {
        return deploys.findFirstByProjectIdOrderByDeployedAtDesc(projectId).map(DeployEventDto::from).orElse(null);
    }

    /** Idempotent on (source, sha, project). Returns 1 when a row was inserted or refreshed. */
    public int upsert(UUID projectId, String repo, String sha, String branch, String message, String environment,
                      String state, Instant deployedAt, Enums.DeploySource source) {
        if (sha == null || sha.isBlank()) {
            return 0;
        }
        return jdbc.update(UPSERT, projectId, repo, sha, branch, truncate(message, 500), environment, state,
                Timestamp.from(deployedAt == null ? Instant.now() : deployedAt), source.name());
    }

    static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
