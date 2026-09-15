package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cached number from one source. Writes go through {@code SnapshotWriter} (an upsert on the
 * unique period index) — never through the repository's save(), so re-runs cannot duplicate.
 */
@Entity
@Table(name = "metric_snapshot")
@Getter
@Setter
public class MetricSnapshot {

    public static final String PERIOD_DAY = "day";
    public static final String PERIOD_HOUR = "hour";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String source;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "dimension_key")
    private String dimensionKey;

    @Column(nullable = false)
    private String period;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_hour")
    private Integer periodHour;

    @Column(name = "metric_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal metricValue;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}
