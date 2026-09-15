package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.BookvasTenantCache;

import java.util.List;
import java.util.UUID;

public interface BookvasTenantCacheRepository extends JpaRepository<BookvasTenantCache, UUID> {

    List<BookvasTenantCache> findAllByOrderByTenantCreatedAtDesc();

    List<BookvasTenantCache> findBySubscriptionStatusIgnoreCase(String subscriptionStatus);
}
