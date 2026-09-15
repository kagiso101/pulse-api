package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A Bookvas platform-event diary row as last seen by the Bookvas connector. */
@Entity
@Table(name = "bookvas_platform_event")
@Getter
@Setter
public class BookvasPlatformEvent {

    @Id
    private UUID id;

    @Column(name = "happened_at", nullable = false)
    private Instant happenedAt;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "tenant_name")
    private String tenantName;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String message;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> raw;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();
}
