package pulse_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.BookvasPlatformEvent;

import java.util.List;
import java.util.UUID;

public interface BookvasPlatformEventRepository extends JpaRepository<BookvasPlatformEvent, UUID> {

    List<BookvasPlatformEvent> findAllByOrderByHappenedAtDesc(Pageable pageable);

    long countByResolvedAtIsNullAndSeverityIgnoreCase(String severity);
}
