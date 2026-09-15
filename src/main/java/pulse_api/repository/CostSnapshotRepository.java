package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.CostSnapshot;
import pulse_api.entity.Enums;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CostSnapshotRepository extends JpaRepository<CostSnapshot, UUID> {

    List<CostSnapshot> findByPeriodMonthOrderByProviderAscSourceAsc(LocalDate periodMonth);

    List<CostSnapshot> findByPeriodMonthBetween(LocalDate from, LocalDate to);

    Optional<CostSnapshot> findByProviderAndPeriodMonthAndSource(Enums.CostProvider provider, LocalDate periodMonth, Enums.CostSource source);
}
