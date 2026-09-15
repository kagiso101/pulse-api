package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Tenant + current subscription as last pulled from Bookvas. Drives the tenant leaderboard. */
@Entity
@Table(name = "bookvas_tenant_cache")
@Getter
@Setter
public class BookvasTenantCache {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String slug;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    private String status;

    private Boolean active;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "plan_code")
    private String planCode;

    @Column(name = "subscription_status")
    private String subscriptionStatus;

    @Column(name = "previous_subscription_status")
    private String previousSubscriptionStatus;

    @Column(name = "is_founder", nullable = false)
    private boolean founder;

    @Column(name = "grace_until")
    private Instant graceUntil;

    @Column(name = "tenant_created_at")
    private Instant tenantCreatedAt;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();
}
