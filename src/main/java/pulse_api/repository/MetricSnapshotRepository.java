package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pulse_api.entity.MetricSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregation lives here (contract §7: connectors are dumb fetchers). All range queries are over
 * {@code period='day'} rows keyed by the Africa/Johannesburg day in {@code period_start}.
 */
public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, UUID> {

    Optional<MetricSnapshot> findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(UUID projectId, String metricKey);

    boolean existsByProjectIdAndSource(UUID projectId, String source);

    boolean existsByProjectIdAndMetricKeyAndPeriodStartBetween(UUID projectId, String metricKey, LocalDate from, LocalDate to);

    List<MetricSnapshot> findByProjectIdAndMetricKeyAndPeriodAndPeriodStartOrderByPeriodHourAsc(
            UUID projectId, String metricKey, String period, LocalDate periodStart);

    List<MetricSnapshot> findByProjectIdAndMetricKeyAndPeriodStart(UUID projectId, String metricKey, LocalDate periodStart);

    @Query("SELECT MAX(m.capturedAt) FROM MetricSnapshot m")
    Instant maxCapturedAt();

    @Query("SELECT MAX(m.capturedAt) FROM MetricSnapshot m WHERE m.projectId = :projectId")
    Instant maxCapturedAtForProject(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COALESCE(SUM(m.metricValue), 0) FROM MetricSnapshot m
            WHERE m.projectId = :projectId AND m.metricKey = :key AND m.period = 'day'
              AND m.periodStart BETWEEN :from AND :to
            """)
    BigDecimal sumForProject(@Param("projectId") UUID projectId, @Param("key") String key,
                             @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT COALESCE(SUM(m.metricValue), 0) FROM MetricSnapshot m
            WHERE m.projectId IN :projectIds AND m.metricKey = :key AND m.period = 'day'
              AND m.periodStart BETWEEN :from AND :to
            """)
    BigDecimal sumForProjects(@Param("projectIds") Collection<UUID> projectIds, @Param("key") String key,
                              @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT new pulse_api.repository.DayValue(m.periodStart, SUM(m.metricValue)) FROM MetricSnapshot m
            WHERE m.projectId = :projectId AND m.metricKey = :key AND m.period = 'day'
              AND m.periodStart BETWEEN :from AND :to
            GROUP BY m.periodStart ORDER BY m.periodStart
            """)
    List<DayValue> dailySeries(@Param("projectId") UUID projectId, @Param("key") String key,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Sum per dimension (page path, session source, status ...) for one metric over a range, biggest first. */
    @Query("""
            SELECT new pulse_api.repository.KeyValue(m.dimensionKey, SUM(m.metricValue)) FROM MetricSnapshot m
            WHERE m.projectId = :projectId AND m.metricKey = :key AND m.period = 'day'
              AND m.periodStart BETWEEN :from AND :to AND m.dimensionKey IS NOT NULL
            GROUP BY m.dimensionKey ORDER BY SUM(m.metricValue) DESC
            """)
    List<KeyValue> sumByDimension(@Param("projectId") UUID projectId, @Param("key") String key,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Sum per metric key for keys sharing a prefix (e.g. {@code event:}) over a range. */
    @Query("""
            SELECT new pulse_api.repository.KeyValue(m.metricKey, SUM(m.metricValue)) FROM MetricSnapshot m
            WHERE m.projectId = :projectId AND m.metricKey LIKE :prefix AND m.period = 'day'
              AND m.periodStart BETWEEN :from AND :to
            GROUP BY m.metricKey ORDER BY SUM(m.metricValue) DESC
            """)
    List<KeyValue> sumByKeyPrefix(@Param("projectId") UUID projectId, @Param("prefix") String prefixLike,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);
}
