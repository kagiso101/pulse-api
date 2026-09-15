package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "project")
@Getter
@Setter
public class Project {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.ProjectKind kind;

    @Column(name = "ga4_property_id")
    private String ga4PropertyId;

    @Column(name = "ga4_measurement_id")
    private String ga4MeasurementId;

    @Column(name = "site_url")
    private String siteUrl;

    @Column(name = "api_health_url")
    private String apiHealthUrl;

    @Column(name = "netlify_site_id")
    private String netlifySiteId;

    @Column(name = "cloud_run_service")
    private String cloudRunService;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "github_repos", nullable = false, columnDefinition = "text[]")
    private List<String> githubRepos = new ArrayList<>();

    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "auto_discovered", nullable = false)
    private boolean autoDiscovered;

    @Column(name = "discovered_at")
    private Instant discoveredAt;

    @Column(name = "client_view_token_hash")
    private String clientViewTokenHash;

    @Column(name = "client_view_token_created_at")
    private Instant clientViewTokenCreatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
