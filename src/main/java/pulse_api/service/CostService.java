package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.CostDtos.CostReport;
import pulse_api.dto.CostDtos.ProviderRow;
import pulse_api.dto.CostDtos.TrendPoint;
import pulse_api.entity.CostSnapshot;
import pulse_api.entity.Enums;
import pulse_api.repository.CostSnapshotRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Contract §2.6 — connector rows plus manual entries, one report per month, six-month trend. */
@Service
@RequiredArgsConstructor
public class CostService {

    private final CostSnapshotRepository costs;
    private final RangeResolver ranges;

    @Transactional(readOnly = true)
    public CostReport report(String month) {
        YearMonth ym = parseMonth(month);
        LocalDate first = ym.atDay(1);
        List<ProviderRow> rows = costs.findByPeriodMonthOrderByProviderAscSourceAsc(first).stream()
                .map(c -> new ProviderRow(c.getProvider(), c.getAmountCents(), c.getSource(), c.getCapturedAt()))
                .toList();
        long total = rows.stream().mapToLong(ProviderRow::amountCents).sum();

        Map<LocalDate, Long> trend = new TreeMap<>();
        for (int i = 5; i >= 0; i--) {
            trend.put(ym.minusMonths(i).atDay(1), 0L);
        }
        for (CostSnapshot c : costs.findByPeriodMonthBetween(ym.minusMonths(5).atDay(1), first)) {
            trend.merge(c.getPeriodMonth(), c.getAmountCents(), Long::sum);
        }
        List<TrendPoint> points = new ArrayList<>();
        trend.forEach((m, cents) -> points.add(new TrendPoint(YearMonth.from(m).toString(), cents)));
        return new CostReport(ym.toString(), total, rows, points);
    }

    @Transactional
    public CostReport upsertManual(Enums.CostProvider provider, String month, long amountCents) {
        LocalDate first = parseMonth(month).atDay(1);
        CostSnapshot row = costs.findByProviderAndPeriodMonthAndSource(provider, first, Enums.CostSource.manual)
                .orElseGet(CostSnapshot::new);
        row.setProvider(provider);
        row.setPeriodMonth(first);
        row.setSource(Enums.CostSource.manual);
        row.setAmountCents(amountCents);
        row.setCapturedAt(Instant.now());
        costs.save(row);
        return report(month);
    }

    /** Connector path (GCP billing export): replaces the connector row for the month. */
    @Transactional
    public void upsertConnector(Enums.CostProvider provider, LocalDate firstOfMonth, long amountCents, String currency,
                                Map<String, Object> breakdown) {
        CostSnapshot row = costs.findByProviderAndPeriodMonthAndSource(provider, firstOfMonth, Enums.CostSource.connector)
                .orElseGet(CostSnapshot::new);
        row.setProvider(provider);
        row.setPeriodMonth(firstOfMonth);
        row.setSource(Enums.CostSource.connector);
        row.setAmountCents(amountCents);
        row.setCurrency(currency == null ? "ZAR" : currency);
        row.setBreakdown(breakdown);
        row.setCapturedAt(Instant.now());
        costs.save(row);
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(ranges.today());
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }
}
