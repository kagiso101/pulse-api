package pulse_api.connector;

import java.time.Instant;
import java.time.LocalDate;

/** The Africa/Johannesburg days a fetch should (re)load, plus the wall-clock instant of the run. */
public record FetchWindow(LocalDate startDay, LocalDate endDay, Instant now) {}
