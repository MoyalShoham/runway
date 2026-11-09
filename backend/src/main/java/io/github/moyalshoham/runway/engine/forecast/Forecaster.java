package io.github.moyalshoham.runway.engine.forecast;

import io.github.moyalshoham.runway.engine.domain.CompanySnapshot;
import io.github.moyalshoham.runway.engine.domain.Money;
import io.github.moyalshoham.runway.engine.model.CashModel;
import io.github.moyalshoham.runway.engine.model.ForecastSettings;
import io.github.moyalshoham.runway.engine.scenario.Scenario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Monte Carlo cash forecast. Each path samples customer payment delays, recurring-item dates and
 * amounts, new documents and irregular weeks; the band is the 10th to 90th percentile of the
 * paths' daily balances. Same inputs and seed, same forecast.
 */
public final class Forecaster {

    /** Schedule items that land in fewer than this share of paths are left out of the schedule. */
    static final double SCHEDULE_MIN_PROBABILITY = 0.5;

    public Forecast forecast(CompanySnapshot snapshot, ForecastSettings settings) {
        return forecast(CashModel.build(snapshot, settings), List.of());
    }

    public Forecast forecast(CashModel model, List<Scenario> scenarios) {
        ForecastSettings settings = model.settings();
        LocalDate asOf = model.snapshot().asOf();
        int horizon = settings.horizonDays();
        int paths = settings.paths();
        PathSimulator simulator = new PathSimulator(model, scenarios);
        Accumulator acc = new Accumulator(asOf, horizon);
        BigDecimal[][] balances = new BigDecimal[paths][horizon];
        for (int p = 0; p < paths; p++) {
            acc.startPath(p);
            simulator.simulate(p, acc);
            BigDecimal[] flows = acc.flows();
            BigDecimal balance = model.snapshot().balance();
            for (int h = 0; h < horizon; h++) {
                balance = balance.add(flows[h]);
                balances[p][h] = balance;
            }
        }

        inflateDispersion(balances, settings.dispersion());

        BigDecimal minimum = settings.minimumBalance();
        List<DailyPoint> days = new ArrayList<>(horizon);
        BigDecimal[] column = new BigDecimal[paths];
        for (int h = 0; h < horizon; h++) {
            int below = 0;
            for (int p = 0; p < paths; p++) {
                column[p] = balances[p][h];
                if (column[p].compareTo(minimum) < 0) {
                    below++;
                }
            }
            Arrays.sort(column);
            days.add(new DailyPoint(
                    asOf.plusDays(h + 1L),
                    quantile(column, 0.10),
                    quantile(column, 0.50),
                    quantile(column, 0.90),
                    below / (double) paths));
        }

        List<ScheduleItem> schedule = acc.schedule(paths).stream()
                .filter(i -> i.probability() >= SCHEDULE_MIN_PROBABILITY)
                .sorted(Comparator.comparing(ScheduleItem::date).thenComparing(ScheduleItem::key))
                .toList();

        BigDecimal[] minimums = new BigDecimal[paths];
        for (int p = 0; p < paths; p++) {
            BigDecimal min = balances[p][0];
            for (BigDecimal b : balances[p]) {
                min = b.compareTo(min) < 0 ? b : min;
            }
            minimums[p] = min;
        }

        List<Alert> alerts = new ArrayList<>();
        double alertP = settings.alertProbability();
        AlertDetector.detect(Alert.Kind.BELOW_ZERO, Money.ZERO, alertP, asOf, balances, days, schedule)
                .ifPresent(alerts::add);
        if (minimum.compareTo(Money.ZERO) != 0) {
            AlertDetector.detect(Alert.Kind.BELOW_MINIMUM, minimum, alertP, asOf, balances, days, schedule)
                    .ifPresent(alerts::add);
        }
        alerts.sort(Comparator.comparing(Alert::date).thenComparing(Alert::kind));

        return new Forecast(
                asOf,
                model.snapshot().balance(),
                minimum,
                paths,
                days,
                acc.componentTotals(paths),
                schedule,
                alerts,
                AlertDetector.breachProbability(minimums, minimum),
                AlertDetector.breachProbability(minimums, Money.ZERO),
                model.transactionPatterns(),
                model.documentPatterns(),
                model.doubtfulInvoices(),
                model.customers(),
                model.diagnostics());
    }

    /**
     * Moves every path away from the per-day median path by {@code factor}: balance' = median +
     * factor * (balance - median). Medians are unchanged; spread, breach probabilities and alerts all
     * widen together, so they stay consistent with each other.
     */
    static void inflateDispersion(BigDecimal[][] balances, double factor) {
        if (factor == 1.0) {
            return;
        }
        int paths = balances.length;
        BigDecimal[] column = new BigDecimal[paths];
        for (int h = 0; h < balances[0].length; h++) {
            for (int p = 0; p < paths; p++) {
                column[p] = balances[p][h];
            }
            Arrays.sort(column);
            BigDecimal median = quantile(column, 0.5);
            for (int p = 0; p < paths; p++) {
                BigDecimal deviation = balances[p][h].subtract(median);
                balances[p][h] = median.add(Money.times(deviation, factor));
            }
        }
    }

    /** Nearest-rank quantile of a sorted array. */
    static BigDecimal quantile(BigDecimal[] sorted, double q) {
        int rank = (int) Math.ceil(q * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }
}
