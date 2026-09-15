package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.Enums;
import pulse_api.entity.Prospect;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProspectRepository extends JpaRepository<Prospect, UUID> {

    List<Prospect> findAllByOrderByUpdatedAtDesc();

    List<Prospect> findByStatusOrderByUpdatedAtDesc(Enums.ProspectStatus status);

    List<Prospect> findByNextActionDateBeforeAndStatusNotInOrderByNextActionDateAsc(
            LocalDate before, Collection<Enums.ProspectStatus> excluded);

    Optional<Prospect> findFirstByNameIgnoreCaseAndPhone(String name, String phone);

    Optional<Prospect> findFirstByNameIgnoreCaseAndPhoneIsNull(String name);
}
