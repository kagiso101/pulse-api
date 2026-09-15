package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pulse_api.entity.MetricSnapshot;
import pulse_api.repository.DayValue;
import pulse_api.repository.KeyValue;
import pulse_api.repository.MetricSnapshotRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small, typed helpers over the snapshot table shared by the overview, dashboard, Ask and summary. */
@Service
@RequiredArgsConstructor
public class MetricQueryService {

    public static final String SOURCE_GA4 = "ga4";
    public static final String SOURCE_BOOKVAS = "bookvas";
    public static final String ACTIVE_USERS = "active_users";
    public static final String SESSIONS = "sessions";
    public static final String PAGE_VIEWS = "page_views";
    public static final String PAGE_VIEWS_BY_PATH = "page_views:path";
    public static final String SESSIONS_BY_SOURCE = "sessions:source";
    public static final String EVENT_PREFIX = "event:";

    private final MetricSnapshotRepository snapshots;

    public boolean hasGa4Data(UUID projectId) {
        return snapshots.existsByProjectIdAndSource(projectId, SOURCE_GA4);
    }

    public long sum(UUID projectId, String key, RangeResolver.ResolvedRange r) {
        return snapshots.sumForProject(projectId, key, r.startDay(), r.endDay()).longValue();
    }

    /** Null when no row for that key exists in the range (distinguishes "0" from "not tracked"). */
    public Long sumOrNull(UUID projectId, String key, RangeResolver.ResolvedRange r) {
        if (!snapshots.existsByProjectIdAndMetricKeyAndPeriodStartBetween(projectId, key, r.startDay(), r.endDay())) {
            return null;
        }
        return sum(projectId, key, r);
    }

    public Long latestValue(UUID projectId, String key) {
        return snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(projectId, key)
                .map(s -> s.getMetricValue().longValue()).orElse(null);
    }

    public MetricSnapshot latest(UUID projectId, String key) {
        return snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(projectId, key).orElse(null);
    }

    /** Day → value for every day in the range, zero-filled. */
    public Map<LocalDate, Long> dailySeries(UUID projectId, String key, RangeResolver.ResolvedRange r) {
        Map<LocalDate, Long> series = new LinkedHashMap<>();
        for (LocalDate day : r.days()) {
            series.put(day, 0L);
        }
        for (DayValue v : snapshots.dailySeries(projectId, key, r.startDay(), r.endDay())) {
            series.put(v.day(), v.value().longValue());
        }
        return series;
    }

    /** 24 hourly points for {@code day} if hour snapshots exist, else the day value at the current hour. */
    public List<Long> hourlySeries(UUID projectId, String key, LocalDate day, int currentHour) {
        List<Long> points = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            points.add(0L);
        }
        List<MetricSnapshot> hours = snapshots.findByProjectIdAndMetricKeyAndPeriodAndPeriodStartOrderByPeriodHourAsc(
                projectId, key, MetricSnapshot.PERIOD_HOUR, day);
        if (!hours.isEmpty()) {
            for (MetricSnapshot h : hours) {
                if (h.getPeriodHour() != null && h.getPeriodHour() >= 0 && h.getPeriodHour() < 24) {
                    points.set(h.getPeriodHour(), h.getMetricValue().longValue());
                }
            }
            return points;
        }
        BigDecimal dayValue = snapshots.sumForProject(projectId, key, day, day);
        points.set(Math.max(0, Math.min(23, currentHour)), dayValue.longValue());
        return points;
    }

    public List<KeyValue> topDimensions(UUID projectId, String key, RangeResolver.ResolvedRange r, int limit) {
        List<KeyValue> all = snapshots.sumByDimension(projectId, key, r.startDay(), r.endDay());
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    public List<KeyValue> events(UUID projectId, RangeResolver.ResolvedRange r) {
        return snapshots.sumByKeyPrefix(projectId, EVENT_PREFIX + "%", r.startDay(), r.endDay()).stream()
                .map(kv -> new KeyValue(kv.key().substring(EVENT_PREFIX.length()), kv.value()))
                .toList();
    }
}
