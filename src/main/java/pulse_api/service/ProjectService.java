package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.ProjectDto;
import pulse_api.dto.ProjectUpsert;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.exception.ConflictException;
import pulse_api.exception.ResourceNotFoundException;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.MetricSnapshotRepository;
import pulse_api.repository.ProjectRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Registry CRUD (contract §2.1). Delete is soft: {@code active=false}, history stays. */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projects;
    private final MetricSnapshotRepository snapshots;
    private final AlertEventRepository alertEvents;
    private final UptimeStatusService uptime;

    public List<ProjectDto> list() {
        return projects.findAllByOrderByActiveDescSortOrderAscNameAsc().stream().map(this::toDto).toList();
    }

    public Project requireBySlug(String slug) {
        return projects.findBySlug(slug).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + slug));
    }

    public Project requireById(UUID id) {
        return projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }

    @Transactional
    public ProjectDto create(ProjectUpsert body) {
        if (projects.existsBySlug(body.slug())) {
            throw new ConflictException("A project with slug '" + body.slug() + "' already exists");
        }
        Project project = new Project();
        apply(project, body);
        return toDto(projects.save(project));
    }

    @Transactional
    public ProjectDto update(UUID id, ProjectUpsert body) {
        Project project = requireById(id);
        if (!project.getSlug().equals(body.slug()) && projects.existsBySlug(body.slug())) {
            throw new ConflictException("A project with slug '" + body.slug() + "' already exists");
        }
        apply(project, body);
        return toDto(projects.save(project));
    }

    @Transactional
    public void softDelete(UUID id) {
        Project project = requireById(id);
        project.setActive(false);
        projects.save(project);
    }

    private static void apply(Project p, ProjectUpsert b) {
        p.setSlug(b.slug());
        p.setName(b.name().trim());
        p.setKind(b.kind());
        p.setGa4PropertyId(blankToNull(b.ga4PropertyId()));
        p.setSiteUrl(blankToNull(b.siteUrl()));
        p.setApiHealthUrl(blankToNull(b.apiHealthUrl()));
        p.setNetlifySiteId(blankToNull(b.netlifySiteId()));
        p.setCloudRunService(blankToNull(b.cloudRunService()));
        p.setGithubRepos(b.githubRepos() == null ? new ArrayList<>() : new ArrayList<>(b.githubRepos()));
        p.setColor(blankToNull(b.color()));
        if (b.sortOrder() != null) {
            p.setSortOrder(b.sortOrder());
        }
        if (b.active() != null) {
            p.setActive(b.active());
        }
    }

    public ProjectDto toDto(Project p) {
        Enums.UpState up = p.isActive() ? uptime.status(p) : Enums.UpState.unknown;
        var status = new ProjectDto.Status(up,
                alertEvents.countByProjectIdAndAcknowledgedAtIsNull(p.getId()),
                snapshots.maxCapturedAtForProject(p.getId()));
        return new ProjectDto(p.getId(), p.getSlug(), p.getName(), p.getKind(), p.getGa4PropertyId(), p.getSiteUrl(),
                p.getApiHealthUrl(), p.getNetlifySiteId(), p.getCloudRunService(),
                p.getGithubRepos() == null ? List.of() : List.copyOf(p.getGithubRepos()), p.getColor(),
                p.getSortOrder(), p.isActive(), p.isAutoDiscovered(), p.getDiscoveredAt(),
                p.getClientViewTokenHash() != null, status);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
