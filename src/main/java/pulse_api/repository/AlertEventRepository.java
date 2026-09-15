package pulse_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.AlertEvent;

import java.util.List;
import java.util.UUID;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    List<AlertEvent> findByAcknowledgedAtIsNullOrderByFiredAtDesc(Pageable pageable);

    List<AlertEvent> findByAcknowledgedAtIsNotNullOrderByFiredAtDesc(Pageable pageable);

    List<AlertEvent> findAllByOrderByFiredAtDesc(Pageable pageable);

    boolean existsByDedupeKeyAndAcknowledgedAtIsNull(String dedupeKey);

    long countByAcknowledgedAtIsNull();

    long countByProjectIdAndAcknowledgedAtIsNull(UUID projectId);
}
