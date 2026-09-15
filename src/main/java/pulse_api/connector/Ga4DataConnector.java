package pulse_api.connector;

import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import com.google.analytics.data.v1beta.BetaAnalyticsDataSettings;
import com.google.analytics.data.v1beta.DateRange;
import com.google.analytics.data.v1beta.Dimension;
import com.google.analytics.data.v1beta.Filter;
import com.google.analytics.data.v1beta.FilterExpression;
import com.google.analytics.data.v1beta.Metric;
import com.google.analytics.data.v1beta.OrderBy;
import com.google.analytics.data.v1beta.Row;
import com.google.analytics.data.v1beta.RunReportRequest;
import com.google.analytics.data.v1beta.RunReportResponse;
import com.google.api.gax.core.FixedCredentialsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pulse_api.entity.Project;
import pulse_api.repository.MetricSnapshotRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.MetricQueryService;
import pulse_api.service.SnapshotWriter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static pulse_api.service.MetricQueryService.*;

/**
 * GA4 Data API (spec §4.1). Per project with a property id, per day: activeUsers / sessions /
 * screenPageViews, top 10 pagePath, sessionSource and the tracked event counts. The first pull for
 * a project backfills 30 days; later runs reload the window (yesterday + today) so partial numbers
 * converge through the snapshot upsert.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ga4DataConnector implements Connector {

    static final List<String> TRACKED_EVENTS = List.of(
            "file_download", "click", "scroll", "booking_started", "slot_selected", "deposit_initiated", "purchase");
    private static final DateTimeFormatter GA_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int BACKFILL_DAYS = 30;
    private static final int TOP_N = 10;

    private final GoogleCredentialsFactory credentials;
    private final ProjectRepository projects;
    private final MetricSnapshotRepository snapshots;
    private final SnapshotWriter writer;

    @Override
    public String source() {
        return MetricQueryService.SOURCE_GA4;
    }

    @Override
    public boolean configured() {
        return credentials.ga4Configured();
    }

    @Override
    public FetchResult fetch(FetchWindow window) {
        List<Project> targets = projects.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .filter(p -> p.getGa4PropertyId() != null && !p.getGa4PropertyId().isBlank()).toList();
        if (targets.isEmpty()) {
            return FetchResult.of(0, List.of(source() + ": no project has a ga4PropertyId yet (run ga4-discovery)"));
        }
        int written = 0;
        List<String> errors = new ArrayList<>();
        try {
            var settings = BetaAnalyticsDataSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials.ga4Credentials()))
                    .build();
            try (BetaAnalyticsDataClient client = BetaAnalyticsDataClient.create(settings)) {
                for (Project p : targets) {
                    try {
                        written += pullProject(client, p, window);
                    } catch (Exception e) {
                        log.warn("GA4 pull failed for {}: {}", p.getSlug(), e.toString());
                        errors.add(source() + " (" + p.getSlug() + "): " + FetchResult.describe(e));
                    }
                }
            }
        } catch (Exception e) {
            return FetchResult.failed(source(), e);
        }
        return FetchResult.of(written, errors);
    }

    private int pullProject(BetaAnalyticsDataClient client, Project p, FetchWindow window) {
        String property = "properties/" + p.getGa4PropertyId().trim();
        LocalDate start = snapshots.existsByProjectIdAndSource(p.getId(), source())
                ? window.startDay() : window.endDay().minusDays(BACKFILL_DAYS - 1L);
        DateRange range = DateRange.newBuilder().setStartDate(ISO.format(start)).setEndDate(ISO.format(window.endDay())).build();
        int[] written = {0};

        // 1. daily totals
        RunReportResponse totals = client.runReport(RunReportRequest.newBuilder().setProperty(property)
                .addDateRanges(range).addDimensions(dim("date"))
                .addMetrics(metric("activeUsers")).addMetrics(metric("sessions")).addMetrics(metric("screenPageViews"))
                .setLimit(1000).build());
        for (Row row : totals.getRowsList()) {
            LocalDate day = day(row);
            if (day == null) continue;
            written[0] += writer.day(p.getId(), source(), ACTIVE_USERS, null, day, number(row, 0));
            written[0] += writer.day(p.getId(), source(), SESSIONS, null, day, number(row, 1));
            written[0] += writer.day(p.getId(), source(), PAGE_VIEWS, null, day, number(row, 2));
        }

        // 2. top pages per day, 3. sources per day
        written[0] += topPerDay(client, property, range, "pagePath", "screenPageViews",
                (dayKey, value) -> writer.day(p.getId(), source(), PAGE_VIEWS_BY_PATH, dayKey.dimension(), dayKey.day(), value));
        written[0] += topPerDay(client, property, range, "sessionSource", "sessions",
                (dayKey, value) -> writer.day(p.getId(), source(), SESSIONS_BY_SOURCE, dayKey.dimension(), dayKey.day(), value));

        // 4. tracked events
        Filter.InListFilter.Builder inList = Filter.InListFilter.newBuilder();
        TRACKED_EVENTS.forEach(inList::addValues);
        RunReportResponse events = client.runReport(RunReportRequest.newBuilder().setProperty(property)
                .addDateRanges(range).addDimensions(dim("date")).addDimensions(dim("eventName"))
                .addMetrics(metric("eventCount"))
                .setDimensionFilter(FilterExpression.newBuilder().setFilter(
                        Filter.newBuilder().setFieldName("eventName").setInListFilter(inList)))
                .setLimit(10000).build());
        for (Row row : events.getRowsList()) {
            LocalDate day = day(row);
            if (day == null) continue;
            String event = row.getDimensionValues(1).getValue();
            written[0] += writer.day(p.getId(), source(), EVENT_PREFIX + event, event, day, number(row, 0));
        }
        return written[0];
    }

    private record DayKey(LocalDate day, String dimension) {}

    private interface DayWriter {
        int write(DayKey key, long value);
    }

    /** One request per dimension for the whole range; the top-N per day is kept in memory. */
    private int topPerDay(BetaAnalyticsDataClient client, String property, DateRange range, String dimension,
                          String metric, DayWriter out) {
        RunReportResponse response = client.runReport(RunReportRequest.newBuilder().setProperty(property)
                .addDateRanges(range).addDimensions(dim("date")).addDimensions(dim(dimension))
                .addMetrics(metric(metric))
                .addOrderBys(OrderBy.newBuilder().setMetric(OrderBy.MetricOrderBy.newBuilder().setMetricName(metric)).setDesc(true))
                .setLimit(10000).build());
        Map<LocalDate, Integer> kept = new HashMap<>();
        int written = 0;
        for (Row row : response.getRowsList()) {
            LocalDate day = day(row);
            if (day == null) continue;
            int count = kept.getOrDefault(day, 0);
            if (count >= TOP_N) continue;
            kept.put(day, count + 1);
            String value = row.getDimensionValues(1).getValue();
            written += out.write(new DayKey(day, value.length() > 500 ? value.substring(0, 500) : value), number(row, 0));
        }
        return written;
    }

    private static Dimension dim(String name) {
        return Dimension.newBuilder().setName(name).build();
    }

    private static Metric metric(String name) {
        return Metric.newBuilder().setName(name).build();
    }

    private static LocalDate day(Row row) {
        String raw = row.getDimensionValues(0).getValue();
        try {
            return LocalDate.parse(raw, GA_DATE);
        } catch (Exception e) {
            return null; // "(other)" buckets
        }
    }

    private static long number(Row row, int index) {
        try {
            return Math.round(Double.parseDouble(row.getMetricValues(index).getValue()));
        } catch (Exception e) {
            return 0;
        }
    }
}
