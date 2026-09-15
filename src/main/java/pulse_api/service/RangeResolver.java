package pulse_api.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Resolves {@code range=today|7d|30d} (default 7d) to Africa/Johannesburg day boundaries
 * (contract §0.6). Days are inclusive; instants are [start, end).
 */
@Component
public class RangeResolver {

    public static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    public static final String TODAY = "today";
    public static final String DAYS_7 = "7d";
    public static final String DAYS_30 = "30d";

    private final Clock clock;

    public RangeResolver(Clock clock) {
        this.clock = clock;
    }

    public record ResolvedRange(String range, LocalDate startDay, LocalDate endDay, Instant startInstant,
                                Instant endInstant, List<LocalDate> days) {
        public int dayCount() {
            return days.size();
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(clock.withZone(ZONE));
    }

    public Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    public ResolvedRange resolve(String range) {
        String normalised = range == null || range.isBlank() ? DAYS_7 : range.trim().toLowerCase();
        LocalDate today = today();
        LocalDate start = switch (normalised) {
            case TODAY -> today;
            case DAYS_7 -> today.minusDays(6);
            case DAYS_30 -> today.minusDays(29);
            default -> throw new IllegalArgumentException("range must be one of today, 7d, 30d");
        };
        return between(normalised, start, today);
    }

    /** A fixed window ending today, e.g. the 30d client view or the summary's "yesterday". */
    public ResolvedRange lastDays(int days) {
        LocalDate today = today();
        return between(days + "d", today.minusDays(days - 1L), today);
    }

    private ResolvedRange between(String label, LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        for (long i = 0; i <= ChronoUnit.DAYS.between(start, end); i++) {
            days.add(start.plusDays(i));
        }
        return new ResolvedRange(label, start, end, startOfDay(start), startOfDay(end.plusDays(1)), days);
    }
}
