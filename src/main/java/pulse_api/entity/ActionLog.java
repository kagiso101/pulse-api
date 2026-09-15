package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "action_log")
@Getter
@Setter
public class ActionLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(nullable = false)
    private String result = "pending";

    private String message;

    @Column(nullable = false)
    private Instant at = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}
