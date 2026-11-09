package io.github.moyalshoham.runway.engine.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A warning that the balance may fall below a threshold.
 *
 * @param probability share of simulated paths that dip below the threshold at some point
 * @param date the day by which at least {@code alertProbability} of paths have dipped below it
 * @param shortfall extra cash needed today to bring the probability under {@code alertProbability}
 * @param lowestDate day of the lowest median balance
 * @param lowestBalance the lowest median balance
 * @param drivers the flows that contribute most, largest first
 */
public record Alert(
        Kind kind,
        Severity severity,
        BigDecimal threshold,
        double probability,
        LocalDate date,
        BigDecimal shortfall,
        LocalDate lowestDate,
        BigDecimal lowestBalance,
        List<Driver> drivers,
        String message) {

    public enum Kind {
        BELOW_MINIMUM,
        BELOW_ZERO
    }

    public enum Severity {
        /** At least 75% of paths breach. */
        LIKELY,
        /** At least the configured alert probability, under 75%. */
        POSSIBLE
    }

    /** A flow that pushes the balance toward the threshold. */
    public record Driver(String label, Component component, LocalDate date, BigDecimal amount, String reason) {}

    public Alert {
        drivers = List.copyOf(drivers);
    }
}
