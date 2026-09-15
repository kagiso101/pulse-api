package pulse_api.repository;

import java.math.BigDecimal;

/** A dimension or metric key with its summed value (JPQL constructor projection). */
public record KeyValue(String key, BigDecimal value) {}
