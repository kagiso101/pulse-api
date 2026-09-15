package pulse_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.DeployEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeployEventRepository extends JpaRepository<DeployEvent, UUID> {

    List<DeployEvent> findAllByOrderByDeployedAtDesc(Pageable pageable);

    List<DeployEvent> findByProjectIdOrderByDeployedAtDesc(UUID projectId, Pageable pageable);

    Optional<DeployEvent> findFirstByProjectIdOrderByDeployedAtDesc(UUID projectId);
}
