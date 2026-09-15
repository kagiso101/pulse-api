package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.Enums;
import pulse_api.entity.UptimeCheck;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, UUID> {

    List<UptimeCheck> findTop3ByProjectIdAndTargetOrderByCheckedAtDesc(UUID projectId, Enums.UptimeTarget target);

    Optional<UptimeCheck> findFirstByProjectIdAndTargetOrderByCheckedAtDesc(UUID projectId, Enums.UptimeTarget target);

    long countByProjectIdAndTargetAndCheckedAtAfter(UUID projectId, Enums.UptimeTarget target, Instant after);

    long countByProjectIdAndTargetAndOkTrueAndCheckedAtAfter(UUID projectId, Enums.UptimeTarget target, Instant after);
}
