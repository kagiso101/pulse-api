package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.AlertState;

public interface AlertStateRepository extends JpaRepository<AlertState, String> {
}
