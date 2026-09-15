package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.AlertRule;
import pulse_api.entity.Enums;

import java.util.List;
import java.util.UUID;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    List<AlertRule> findAllByOrderByCreatedAtAsc();

    List<AlertRule> findByEnabledTrue();

    List<AlertRule> findByKind(Enums.AlertKind kind);
}
