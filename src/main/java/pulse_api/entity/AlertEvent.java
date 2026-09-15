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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "alert_event")
@Getter
@Setter
public class AlertEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.AlertKind kind;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt = Instant.now();

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String detail = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "dedupe_key")
    private String dedupeKey;

    @Column(nullable = false)
    private boolean delivered;

    private String channel;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
}
