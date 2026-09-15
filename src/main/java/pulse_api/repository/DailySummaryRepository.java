package pulse_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.DailySummary;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailySummaryRepository extends JpaRepository<DailySummary, UUID> {

    Optional<DailySummary> findFirstByOrderBySummaryDateDesc();

    List<DailySummary> findAllByOrderBySummaryDateDesc(Pageable pageable);

    Optional<DailySummary> findBySummaryDate(LocalDate summaryDate);
}
