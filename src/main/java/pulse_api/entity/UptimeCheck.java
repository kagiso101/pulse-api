package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "uptime_check")
@Getter
@Setter
public class UptimeCheck {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.UptimeTarget target;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(nullable = false)
    private boolean ok;

    private String error;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt = Instant.now();
}
