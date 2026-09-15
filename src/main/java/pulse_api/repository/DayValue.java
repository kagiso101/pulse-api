package pulse_api.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One point of a daily series (JPQL constructor projection). */
public record DayValue(LocalDate day, BigDecimal value) {}
