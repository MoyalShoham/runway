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
 * @param byArchetype 30-day mean absolute error per business type, engine vs. best naive baseline
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
        Map<String, Map<String, BigDecimal>> byArchetype,
        String gitCommit,
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
     * Shortfall warnings. A cutoff is eligible when the balance is still at or above the threshold;
     * it is a real shortfall when the actual balance first drops below the threshold 7 to 90 days
     * later, so there was at least a week to act.
     *
     * @param recall share of real shortfalls the method warned about at that cutoff
     * @param falseAlarmShare share of the method's alerts after which no shortfall happened within 90 days
     */
    public record ShortfallMetrics(
            int eligible,
            int realShortfalls,
            int warnedRunway,
            int alertsRunway,
            int falseAlarmsRunway,
            double recallRunway,
            double falseAlarmShareRunway,
            int warnedRepeat,
            int alertsRepeat,
            int falseAlarmsRepeat,
            double recallRepeat,
            double falseAlarmShareRepeat,
            int medianLeadDaysWarned) {}
}
