package io.github.moyalshoham.runway.backtest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Results of a backtest over many companies and cutoff dates.
 *
 * @param split "dev" or "holdout"
 * @param seeds company seeds used
 * @param forecasts number of (company, cutoff) forecasts
 * @param horizons error metrics at each horizon
 * @param shortfalls warning metrics per threshold ("minimum", "zero")
 * @param confidence95 95% intervals from a company-level bootstrap for the headline numbers
 * @param byArchetype 30-day mean absolute error per business type, engine vs. naive baselines
 * @param uncommittedChanges true if the working tree differed from {@code gitCommit} when this ran
 */
public record BacktestReport(
        String split,
        List<Long> seeds,
        int forecasts,
        int paths,
        double alertProbability,
        double dispersion,
        List<HorizonMetrics> horizons,
        Map<String, ShortfallMetrics> shortfalls,
        Map<String, Interval> confidence95,
        Map<String, Map<String, BigDecimal>> byArchetype,
        String gitCommit,
        boolean uncommittedChanges,
        double seconds) {

    /**
     * Error of the median forecast at one horizon.
     *
     * @param maeRunway mean absolute error of the engine's median, ILS
     * @param maeFlat mean absolute error of "balance stays flat", ILS
     * @param maeRepeat mean absolute error of "last 30 days repeated", ILS
     * @param medianAeRunway median absolute error of the engine, ILS
     * @param biasRunway mean signed error of the engine (forecast minus actual), ILS
     * @param relativeMaeRunway engine error as a share of the company's average monthly outflow
     * @param coverage share of actual balances inside the engine's 10-90% band (80% is calibrated)
     * @param maeRunwayExFinancing as maeRunway, against the actual balance without owner loans and
     *     dividends made after the cutoff (business-as-usual error)
     */
    public record HorizonMetrics(
            int days,
            int n,
            BigDecimal maeRunway,
            BigDecimal maeFlat,
            BigDecimal maeRepeat,
            BigDecimal medianAeRunway,
            BigDecimal biasRunway,
            double relativeMaeRunway,
            double relativeMaeFlat,
            double relativeMaeRepeat,
            double coverage,
            BigDecimal maeRunwayExFinancing,
            BigDecimal maeFlatExFinancing,
            BigDecimal maeRepeatExFinancing,
            BigDecimal biasRunwayExFinancing,
            double coverageExFinancing) {}

    /**
     * Shortfall warnings for one threshold. A cutoff is eligible when the balance is still at or
     * above the threshold; it is a real shortfall when the actual balance first drops below the
     * threshold 7 to 90 days later, so there was at least a week to act. Cutoffs are 14 days apart
     * and look 90 days ahead, so one shortfall event appears at several cutoffs; {@code events}
     * counts distinct (company, first day below the threshold).
     *
     * @param methods "runway", "lastThirtyDays" (last 30 days repeated) and "cushion" (alert when the
     *     balance is less than one month of outflows above the threshold)
     */
    public record ShortfallMetrics(int eligible, int realShortfalls, int events, Map<String, Warnings> methods) {}

    /**
     * How one method warned.
     *
     * @param recall share of real-shortfall cutoffs at which the method warned
     * @param falseAlarmShare share of the method's alerts after which no shortfall happened within 90 days
     * @param eventsWarnedAny events warned at one or more of the cutoffs that saw them coming
     * @param eventsWarnedFirst events warned at the earliest cutoff that saw them coming
     * @param medianFirstWarningLeadDays median days from the first warning to the event
     */
    public record Warnings(
            int warned,
            int alerts,
            int falseAlarms,
            double recall,
            double falseAlarmShare,
            int eventsWarnedAny,
            int eventsWarnedFirst,
            int medianFirstWarningLeadDays) {}

    /** A 95% interval from resampling companies with replacement. */
    public record Interval(double low, double high) {}
}
