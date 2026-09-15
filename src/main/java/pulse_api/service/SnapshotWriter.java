package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The only way connectors write {@code metric_snapshot}: an upsert on the unique period index,
 * so a job that runs every 15 minutes overwrites today's partial numbers instead of duplicating.
 */
@Component
@RequiredArgsConstructor
public class SnapshotWriter {

    private static final String UPSERT = """
            INSERT INTO metric_snapshot (project_id, source, metric_key, dimension_key, period, period_start, period_hour, metric_value, captured_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (project_id, source, metric_key, COALESCE(dimension_key, ''), period, period_start, COALESCE(period_hour, -1))
            DO UPDATE SET metric_value = EXCLUDED.metric_value, captured_at = now()
            """;

    private final JdbcTemplate jdbc;

    /** One day-level value; returns the number of rows written (always 1). */
    public int day(UUID projectId, String source, String metricKey, String dimensionKey, LocalDate day, Number value) {
        return jdbc.update(UPSERT, projectId, source, metricKey, dimensionKey, "day", day, null, toDecimal(value));
    }

    public int hour(UUID projectId, String source, String metricKey, String dimensionKey, LocalDate day, int hour, Number value) {
        return jdbc.update(UPSERT, projectId, source, metricKey, dimensionKey, "hour", day, hour, toDecimal(value));
    }

    private static BigDecimal toDecimal(Number value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }
}
