package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Project> findAllByOrderByActiveDescSortOrderAscNameAsc();

    List<Project> findByActiveTrueOrderBySortOrderAscNameAsc();

    List<Project> findByActiveTrueAndKind(Enums.ProjectKind kind);

    Optional<Project> findByGa4PropertyId(String ga4PropertyId);

    Optional<Project> findByGa4MeasurementIdIgnoreCase(String measurementId);

    Optional<Project> findByClientViewTokenHash(String hash);

    Optional<Project> findFirstByCloudRunServiceAndActiveTrue(String cloudRunService);

    Optional<Project> findFirstByNetlifySiteIdAndActiveTrue(String netlifySiteId);
}
