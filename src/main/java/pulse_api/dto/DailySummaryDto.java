package pulse_api.dto;

import pulse_api.entity.DailySummary;

import java.time.Instant;
import java.time.LocalDate;

/** Contract §2.2 {@code DailySummary}. */
public record DailySummaryDto(LocalDate date, String body, Instant sentAt) {

    public static DailySummaryDto from(DailySummary s) {
        return new DailySummaryDto(s.getSummaryDate(), s.getBody(), s.getSentAt());
    }
}
