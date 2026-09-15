package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.ClientViewDto;
import pulse_api.dto.ClientViewTokenResponse;
import pulse_api.dto.ProjectDashboardDto;
import pulse_api.entity.Project;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.repository.ProjectRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static pulse_api.service.MetricQueryService.*;

/** Contract §2.1 token endpoints + §3 public card. Read-only, one project, revocable. */
@Service
@RequiredArgsConstructor
public class ClientViewService {

    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final ClientViewTokenService tokens;
    private final MetricQueryService metrics;
    private final UptimeStatusService uptime;
    private final RangeResolver ranges;

    @Value("${app.web.base-url}")
    private String webBaseUrl;

    /** Replaces any existing token; the raw value is returned exactly once. */
    @Transactional
    public ClientViewTokenResponse issue(UUID projectId) {
        Project project = projectService.requireById(projectId);
        String raw = tokens.generate();
        project.setClientViewTokenHash(tokens.hash(raw));
        project.setClientViewTokenCreatedAt(Instant.now());
        projects.save(project);
        return new ClientViewTokenResponse(raw, webBaseUrl.replaceAll("/+$", "") + "/client/" + raw);
    }

    @Transactional
    public void revoke(UUID projectId) {
        Project project = projectService.requireById(projectId);
        project.setClientViewTokenHash(null);
        project.setClientViewTokenCreatedAt(null);
        projects.save(project);
    }

    @Transactional(readOnly = true)
    public ClientViewDto view(String rawToken) {
        Project p = tokens.lookup(rawToken).filter(Project::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Not found"));
        var r = ranges.lastDays(30);
        ClientViewDto.Traffic traffic = null;
        if (metrics.hasGa4Data(p.getId())) {
            Map<LocalDate, Long> users = metrics.dailySeries(p.getId(), ACTIVE_USERS, r);
            Map<LocalDate, Long> sessions = metrics.dailySeries(p.getId(), SESSIONS, r);
            List<ClientViewDto.SeriesPoint> series = new ArrayList<>();
            for (LocalDate day : r.days()) {
                series.add(new ClientViewDto.SeriesPoint(day, users.get(day), sessions.get(day)));
            }
            List<ProjectDashboardDto.TopPage> topPages = metrics.topDimensions(p.getId(), PAGE_VIEWS_BY_PATH, r, 10).stream()
                    .map(kv -> new ProjectDashboardDto.TopPage(kv.key(), kv.value().longValue())).toList();
            traffic = new ClientViewDto.Traffic(series, topPages);
        }
        return new ClientViewDto(p.getName(), p.getSiteUrl(), uptime.status(p), uptime.uptimePct(p, r.startInstant()),
                traffic, Instant.now());
    }
}
