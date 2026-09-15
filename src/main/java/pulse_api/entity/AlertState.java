package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** The alert engine's memory of the last value/state it saw for a condition. */
@Entity
@Table(name = "alert_state")
@Getter
@Setter
public class AlertState {

    @Id
    @Column(name = "state_key")
    private String stateKey;

    @Column(name = "num_value", precision = 20, scale = 4)
    private BigDecimal numValue;

    @Column(name = "text_value")
    private String textValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
