package pulse_api.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.Notice;

import java.util.List;
import java.util.UUID;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    List<Notice> findByReadAtIsNullOrderByCreatedAtDesc();

    List<Notice> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
