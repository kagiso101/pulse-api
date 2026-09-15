package pulse_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.ActionLog;

import java.util.List;
import java.util.UUID;

public interface ActionLogRepository extends JpaRepository<ActionLog, UUID> {

    List<ActionLog> findAllByOrderByAtDesc(Pageable pageable);
}
