package pulse_api.connector;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pulse_api.entity.Enums;
import pulse_api.service.CostService;
import pulse_api.service.RangeResolver;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Current-month GCP cost by service from the standard BigQuery billing export (spec §4.7),
 * net of credits → cost_snapshot(provider=gcp, source=connector). Disabled until
 * {@code BILLING_EXPORT_TABLE} is set; Netlify/Brevo/PayFast stay manual entries.
 */
@Slf4j
@Component
public class BillingConnector implements Connector {

    public static final String SOURCE = "billing";
    private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final DateTimeFormatter INVOICE_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final CostService costs;
    private final RangeResolver ranges;
    private final String table;
    private final String gcpProject;

    public BillingConnector(CostService costs, RangeResolver ranges,
                            @Value("${app.billing.export-table:}") String table,
                            @Value("${app.gcp.project-id:}") String gcpProject) {
        this.costs = costs;
        this.ranges = ranges;
        this.table = table == null ? "" : table.trim();
        this.gcpProject = gcpProject == null ? "" : gcpProject.trim();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean configured() {
        return !table.isBlank() && TABLE.matcher(table).matches();
    }

    @Override
    public boolean partOfMetricsJob() {
        return false;
    }

    @Override
    public FetchResult fetch(FetchWindow window) {
        YearMonth month = YearMonth.from(ranges.today());
        String sql = """
                SELECT service.description AS service,
                       SUM(cost) + SUM(IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)) AS net_cost,
                       ANY_VALUE(currency) AS currency
                FROM `%s`
                WHERE invoice.month = '%s'
                GROUP BY service
                ORDER BY net_cost DESC
                """.formatted(table, INVOICE_MONTH.format(month));
        try {
            BigQueryOptions.Builder options = BigQueryOptions.newBuilder();
            if (!gcpProject.isBlank()) {
                options.setProjectId(gcpProject);
            }
            BigQuery bigQuery = options.build().getService();
            TableResult result = bigQuery.query(QueryJobConfiguration.newBuilder(sql).build());
            Map<String, Object> breakdown = new LinkedHashMap<>();
            double total = 0;
            String currency = null;
            for (FieldValueList row : result.iterateAll()) {
                double cost = row.get("net_cost").isNull() ? 0 : row.get("net_cost").getDoubleValue();
                String service = row.get("service").isNull() ? "unknown" : row.get("service").getStringValue();
                if (currency == null && !row.get("currency").isNull()) {
                    currency = row.get("currency").getStringValue();
                }
                total += cost;
                breakdown.put(service, Math.round(cost * 100));
            }
            LocalDate first = month.atDay(1);
            costs.upsertConnector(Enums.CostProvider.gcp, first, Math.round(total * 100), currency, breakdown);
            return FetchResult.of(1);
        } catch (Exception e) {
            log.warn("Billing export query failed: {}", e.toString());
            return FetchResult.failed(source(), e);
        }
    }
}
