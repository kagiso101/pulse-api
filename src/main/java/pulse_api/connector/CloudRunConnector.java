package pulse_api.connector;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Service;
import com.google.cloud.run.v2.ServiceName;
import com.google.cloud.run.v2.ServicesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.exception.ApiException;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.DeployService;
import pulse_api.service.RangeResolver;
import pulse_api.service.SnapshotWriter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cloud Run Admin v2 with Application Default Credentials (spec §4.5): latest ready revision,
 * image and traffic split → deploy_event + a {@code cloud_run_ready} snapshot; {@link #restart}
 * creates a new revision from the same image by bumping a template annotation.
 */
@Slf4j
@Component
public class CloudRunConnector implements Connector {

    public static final String SOURCE = "cloudrun";
    static final String RESTART_ANNOTATION = "pulse-restarted-at";

    private final ProjectRepository projects;
    private final DeployService deploys;
    private final SnapshotWriter writer;
    private final RangeResolver ranges;
    private final String gcpProject;
    private final String region;

    public CloudRunConnector(ProjectRepository projects, DeployService deploys, SnapshotWriter writer, RangeResolver ranges,
                             @Value("${app.gcp.project-id:}") String gcpProject,
                             @Value("${app.gcp.region:africa-south1}") String region) {
        this.projects = projects;
        this.deploys = deploys;
        this.writer = writer;
        this.ranges = ranges;
        this.gcpProject = gcpProject == null ? "" : gcpProject.trim();
        this.region = region == null || region.isBlank() ? "africa-south1" : region.trim();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean configured() {
        return !gcpProject.isBlank();
    }

    @Override
    public FetchResult fetch(FetchWindow window) {
        List<Project> targets = projects.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .filter(p -> p.getCloudRunService() != null && !p.getCloudRunService().isBlank()).toList();
        if (targets.isEmpty()) {
            return FetchResult.of(0);
        }
        int written = 0;
        List<String> errors = new ArrayList<>();
        try (ServicesClient client = ServicesClient.create()) {
            for (Project p : targets) {
                try {
                    written += pull(client, p);
                } catch (Exception e) {
                    log.warn("Cloud Run pull failed for {}: {}", p.getSlug(), e.toString());
                    errors.add(source() + " (" + p.getSlug() + "): " + FetchResult.describe(e));
                }
            }
        } catch (Exception e) {
            return FetchResult.failed(source(), e);
        }
        return FetchResult.of(written, errors);
    }

    private int pull(ServicesClient client, Project p) {
        Service svc = client.getService(ServiceName.format(gcpProject, region, p.getCloudRunService()));
        String revision = shortName(svc.getLatestReadyRevision());
        String image = svc.getTemplate().getContainersCount() > 0 ? svc.getTemplate().getContainers(0).getImage() : null;
        String traffic = svc.getTrafficStatusesList().stream()
                .map(t -> shortName(t.getRevision()) + "=" + t.getPercent() + "%")
                .collect(Collectors.joining(", "));
        boolean ready = svc.getTerminalCondition().getState() == Condition.State.CONDITION_SUCCEEDED;
        Instant updated = Instant.ofEpochSecond(svc.getUpdateTime().getSeconds(), svc.getUpdateTime().getNanos());

        int written = 0;
        if (!revision.isBlank()) {
            written += deploys.upsert(p.getId(), null, revision, null,
                    "image " + imageTag(image) + (traffic.isBlank() ? "" : "; traffic " + traffic),
                    p.getCloudRunService(), ready ? "ready" : "not-ready", updated, Enums.DeploySource.cloudrun);
        }
        written += writer.day(p.getId(), source(), "cloud_run_ready", p.getCloudRunService(), ranges.today(), ready ? 1 : 0);
        return written;
    }

    /** New revision from the current image: same template, one changed annotation, auto-generated revision name. */
    public String restart(String serviceName) {
        if (!configured()) {
            throw ApiException.notConfigured("Cloud Run connector");
        }
        try (ServicesClient client = ServicesClient.create()) {
            Service current = client.getService(ServiceName.format(gcpProject, region, serviceName));
            Service updated = current.toBuilder()
                    .setTemplate(current.getTemplate().toBuilder()
                            .clearRevision()
                            .putAnnotations(RESTART_ANNOTATION, Instant.now().toString()))
                    .build();
            Service result = client.updateServiceAsync(updated).get(5, TimeUnit.MINUTES);
            return "Cloud Run service " + serviceName + " restarted; latest ready revision "
                    + shortName(result.getLatestReadyRevision());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.upstream("Cloud Run restart failed: " + FetchResult.describe(e));
        }
    }

    private static String shortName(String resourceName) {
        if (resourceName == null) return "";
        int slash = resourceName.lastIndexOf('/');
        return slash < 0 ? resourceName : resourceName.substring(slash + 1);
    }

    private static String imageTag(String image) {
        if (image == null) return "?";
        int slash = image.lastIndexOf('/');
        return slash < 0 ? image : image.substring(slash + 1);
    }
}
