package pulse_api.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeResolverTest {

    // 23:30 UTC on the 15th is already 01:30 on the 16th in Africa/Johannesburg (UTC+2)
    private final RangeResolver ranges = new RangeResolver(Clock.fixed(Instant.parse("2026-09-15T23:30:00Z"), ZoneOffset.UTC));

    @Test
    void todayIsResolvedInJohannesburgNotUtc() {
        var r = ranges.resolve("today");
        assertThat(ranges.today()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(r.startDay()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(r.endDay()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(r.days()).hasSize(1);
        assertThat(r.startInstant()).isEqualTo(Instant.parse("2026-09-15T22:00:00Z"));
        assertThat(r.endInstant()).isEqualTo(Instant.parse("2026-09-16T22:00:00Z"));
    }

    @Test
    void sevenDaysEndsTodayAndIsTheDefault() {
        var r = ranges.resolve("7d");
        assertThat(r.startDay()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(r.endDay()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(r.days()).hasSize(7).startsWith(LocalDate.of(2026, 9, 10)).endsWith(LocalDate.of(2026, 9, 16));
        assertThat(ranges.resolve(null).range()).isEqualTo("7d");
        assertThat(ranges.resolve("").range()).isEqualTo("7d");
    }

    @Test
    void thirtyDaysHasThirtyPoints() {
        var r = ranges.resolve("30D");
        assertThat(r.days()).hasSize(30);
        assertThat(r.startDay()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(r.range()).isEqualTo("30d");
    }

    @Test
    void unknownRangeIsABadRequest() {
        assertThatThrownBy(() -> ranges.resolve("90d")).isInstanceOf(IllegalArgumentException.class);
    }
}
