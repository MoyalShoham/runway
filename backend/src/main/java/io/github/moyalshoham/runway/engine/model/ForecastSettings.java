package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Forecast parameters. Defaults were chosen on the development seeds only (see docs/decisions.md).
 *
 * @param horizonDays days forecast after asOf
 * @param paths Monte Carlo paths
 * @param seed base seed; the same seed and inputs always give the same forecast
 * @param minimumBalance the user's own floor; alerts fire for this and for zero
 * @param alertProbability raise an alert when at least this share of paths dips below a threshold
 * @param baselineWindowDays history used for irregular (non-recurring, non-document) flows
 * @param doubtfulAfterDays invoices overdue longer than this are excluded as doubtful
 * @param delayPriorStrength pseudo-count pulling a customer's payment delays toward everyone's
 * @param discretionaryPattern counterparties whose flows are owner decisions (owner loans,
 *     dividends): they are left out of the projection instead of being extrapolated
 * @param dispersion factor applied to every path's deviation from the median path. The simulation
 *     samples known sources of randomness but not model error (a retainer ending, an employee
 *     leaving), so raw paths are too close together; the factor was fit on the development split so
 *     that 80% of outcomes land inside the 10-90% band
 */
public record ForecastSettings(
        int horizonDays,
        int paths,
        long seed,
        BigDecimal minimumBalance,
        double alertProbability,
        VatSettings vat,
        int baselineWindowDays,
        int doubtfulAfterDays,
        double delayPriorStrength,
        Pattern discretionaryPattern,
        double dispersion) {

    /** Owner loans and dividends, in Hebrew and English. Matched against the normalised counterparty. */
    public static final Pattern DEFAULT_DISCRETIONARY =
            Pattern.compile("(?U)\\b(הלוואת בעלים|הלוואות בעלים|דיבידנד|dividends?|owner loan|shareholder loan)\\b");

    public ForecastSettings {
        Objects.requireNonNull(vat, "vat");
        Objects.requireNonNull(discretionaryPattern, "discretionaryPattern");
        minimumBalance = Money.round(minimumBalance);
        if (horizonDays < 1 || horizonDays > 366) {
            throw new IllegalArgumentException("horizonDays must be 1..366");
        }
        if (paths < 10 || paths > 10_000) {
            throw new IllegalArgumentException("paths must be 10..10000");
        }
        if (!(alertProbability > 0 && alertProbability < 1)) {
            throw new IllegalArgumentException("alertProbability must be in (0, 1)");
        }
        if (!(dispersion >= 1.0 && dispersion <= 5.0)) {
            throw new IllegalArgumentException("dispersion must be in [1, 5]");
        }
        if (baselineWindowDays < 28 || baselineWindowDays % 7 != 0) {
            throw new IllegalArgumentException("baselineWindowDays must be a multiple of 7, at least 28");
        }
    }

    /** Fit on the development split for 80% coverage of the 10-90% band; see docs/decisions.md. */
    public static final double DEFAULT_DISPERSION = 1.6;
    /** Chosen on the development split by F2 score (recall weighted over precision); see docs/decisions.md. */
    public static final double DEFAULT_ALERT_PROBABILITY = 0.5;

    public static ForecastSettings defaults(BigDecimal minimumBalance) {
        return new ForecastSettings(
                90,
                400,
                42L,
                minimumBalance,
                DEFAULT_ALERT_PROBABILITY,
                VatSettings.israel(),
                182,
                180,
                3.0,
                DEFAULT_DISCRETIONARY,
                DEFAULT_DISPERSION);
    }

    public ForecastSettings withPaths(int newPaths) {
        return copy(horizonDays, newPaths, alertProbability, dispersion);
    }

    public ForecastSettings withAlertProbability(double p) {
        return copy(horizonDays, paths, p, dispersion);
    }

    public ForecastSettings withDispersion(double d) {
        return copy(horizonDays, paths, alertProbability, d);
    }

    public ForecastSettings withHorizon(int days) {
        return copy(days, paths, alertProbability, dispersion);
    }

    private ForecastSettings copy(int horizon, int pathCount, double alertP, double disp) {
        return new ForecastSettings(
                horizon,
                pathCount,
                seed,
                minimumBalance,
                alertP,
                vat,
                baselineWindowDays,
                doubtfulAfterDays,
                delayPriorStrength,
                discretionaryPattern,
                disp);
    }
}
