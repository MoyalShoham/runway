package io.github.moyalshoham.runway.engine.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An expected future flow, summarised over the simulated paths.
 *
 * @param date median day on which it lands, over the paths in which it lands within the horizon
 * @param amount mean signed amount over those paths
 * @param probability share of paths in which it lands within the horizon
 */
public record ScheduleItem(
        String key, Component component, String label, LocalDate date, BigDecimal amount, double probability) {}
