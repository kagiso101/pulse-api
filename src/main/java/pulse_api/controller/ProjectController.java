package pulse_api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pulse_api.dto.ClientViewTokenResponse;
import pulse_api.dto.ProjectDashboardDto;
import pulse_api.dto.ProjectDto;
import pulse_api.dto.ProjectUpsert;
import pulse_api.service.ClientViewService;
import pulse_api.service.DashboardService;
import pulse_api.service.ProjectService;

import java.util.List;
import java.util.UUID;

/** Contract §2.1 registry + §2.3 dashboard. */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projects;
    private final DashboardService dashboards;
    private final ClientViewService clientViews;

    @GetMapping
    public List<ProjectDto> list() {
        return projects.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDto create(@Valid @RequestBody ProjectUpsert body) {
        return projects.create(body);
    }

    @PutMapping("/{id}")
    public ProjectDto update(@PathVariable UUID id, @Valid @RequestBody ProjectUpsert body) {
        return projects.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        projects.softDelete(id);
    }

    @PostMapping("/{id}/client-view-token")
    public ClientViewTokenResponse issueClientViewToken(@PathVariable UUID id) {
        return clientViews.issue(id);
    }

    @DeleteMapping("/{id}/client-view-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeClientViewToken(@PathVariable UUID id) {
        clientViews.revoke(id);
    }

    @GetMapping("/{slug}/dashboard")
    public ProjectDashboardDto dashboard(@PathVariable String slug, @RequestParam(required = false) String range) {
        return dashboards.dashboard(slug, range);
    }
}
