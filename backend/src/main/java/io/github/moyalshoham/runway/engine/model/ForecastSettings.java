package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;

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
        double delayPriorStrength) {

    public ForecastSettings {
        Objects.requireNonNull(vat, "vat");
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
        if (baselineWindowDays < 28 || baselineWindowDays % 7 != 0) {
            throw new IllegalArgumentException("baselineWindowDays must be a multiple of 7, at least 28");
        }
    }

    public static ForecastSettings defaults(BigDecimal minimumBalance) {
        return new ForecastSettings(90, 400, 42L, minimumBalance, 0.3, VatSettings.israel(), 182, 180, 3.0);
    }

    public ForecastSettings withPaths(int newPaths) {
        return new ForecastSettings(
                horizonDays,
                newPaths,
                seed,
                minimumBalance,
                alertProbability,
                vat,
                baselineWindowDays,
                doubtfulAfterDays,
                delayPriorStrength);
    }

    public ForecastSettings withAlertProbability(double p) {
        return new ForecastSettings(
                horizonDays,
                paths,
                seed,
                minimumBalance,
                p,
                vat,
                baselineWindowDays,
                doubtfulAfterDays,
                delayPriorStrength);
    }

    public ForecastSettings withHorizon(int days) {
        return new ForecastSettings(
                days,
                paths,
                seed,
                minimumBalance,
                alertProbability,
                vat,
                baselineWindowDays,
                doubtfulAfterDays,
                delayPriorStrength);
    }
}
