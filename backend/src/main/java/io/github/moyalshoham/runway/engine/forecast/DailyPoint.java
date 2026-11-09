package io.github.moyalshoham.runway.engine.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Forecast closing balance for one day: the 10th, 50th and 90th percentile over simulated paths,
 * and the share of paths below the user's minimum on that day.
 */
public record DailyPoint(
        LocalDate date, BigDecimal p10, BigDecimal p50, BigDecimal p90, double probabilityBelowMinimum) {}
