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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "cost_snapshot")
@Getter
@Setter
public class CostSnapshot {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.CostProvider provider;

    /** Always the first day of the month. */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private String currency = "ZAR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.CostSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> breakdown;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();
}
