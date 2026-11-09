package io.github.moyalshoham.runway.backtest;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.CompanySnapshot;
import io.github.moyalshoham.runway.engine.domain.Money;
import io.github.moyalshoham.runway.engine.forecast.Alert;
import io.github.moyalshoham.runway.engine.forecast.Forecast;
import io.github.moyalshoham.runway.engine.forecast.Forecaster;
import io.github.moyalshoham.runway.engine.model.ForecastSettings;
import io.github.moyalshoham.runway.sim.Archetype;
import io.github.moyalshoham.runway.sim.CompanyGenerator;
import io.github.moyalshoham.runway.sim.SyntheticCompany;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Walk-forward backtest: for each synthetic company, forecast from a cutoff every 14 days using only
 * data known at the cutoff, then compare with what actually happened.
 */
public final class Backtester {

    public static final int[] HORIZONS = {7, 30, 90};
    public static final LocalDate FIRST_CUTOFF = LocalDate.of(2024, 7, 1);
    public static final int CUTOFF_STEP_DAYS = 14;
    /** A shortfall closer than this is too late to act on and is not counted. */
    public static final int MIN_LEAD_DAYS = 7;

    /** Per-threshold outcome at one cutoff. */
    /**
     * Per-threshold outcome at one cutoff.
     *
     * @param actualFirstBreach days until the actual balance first fell below the threshold, -1 if
     *     it did not within the horizon
     * @param cushionAlert the "one month of cushion" rule: alert when the balance is less than the
     *     last 30 days' outflows above the threshold
     */
    record Outcome(
            boolean eligible, int actualFirstBreach, boolean runwayAlert, boolean repeatAlert, boolean cushionAlert) {}

    /** Everything measured at one (company, cutoff). */
    record CutoffResult(
            long seed,
            Archetype archetype,
            LocalDate cutoff,
            double monthlyOutflow,
            BigDecimal[] actual,
            BigDecimal[] p10,
            BigDecimal[] p50,
            BigDecimal[] p90,
            BigDecimal[] flat,
            BigDecimal[] repeat,
            BigDecimal[] ownerFinancing,
            Map<String, Outcome> outcomes) {}

    private final CompanyGenerator generator = new CompanyGenerator();
    private final Forecaster forecaster = new Forecaster();

    public List<CutoffResult> run(List<Long> seeds, Function<BigDecimal, ForecastSettings> settings) {
        return seeds.parallelStream()
                .flatMap(seed -> runCompany(generator.generate(seed), settings).stream())
                .toList();
    }

    List<CutoffResult> runCompany(SyntheticCompany c, Function<BigDecimal, ForecastSettings> settingsFor) {
        ForecastSettings settings = settingsFor.apply(c.minimumBalance());
        int horizon = settings.horizonDays();
        BigDecimal[] daily = dailyBalances(c);
        List<CutoffResult> results = new ArrayList<>();
        for (LocalDate cutoff = FIRST_CUTOFF;
                !cutoff.plusDays(horizon).isAfter(c.end());
                cutoff = cutoff.plusDays(CUTOFF_STEP_DAYS)) {
            CompanySnapshot s =
                    CompanySnapshot.of(cutoff, c.openingDate(), c.openingBalance(), c.transactions(), c.documents());
            Forecast f = forecaster.forecast(s, settings);
            int base = index(c, cutoff);
            BigDecimal[] flat = Baselines.flat(s.balance(), horizon);
            BigDecimal lastMonthOutflow = Baselines.outflowLast30Days(s.transactions(), cutoff);
            BigDecimal[] repeat = Baselines.lastThirtyDaysRepeated(s.balance(), s.transactions(), cutoff, horizon);
            BigDecimal[] actual = new BigDecimal[HORIZONS.length];
            BigDecimal[] p10 = new BigDecimal[HORIZONS.length];
            BigDecimal[] p50 = new BigDecimal[HORIZONS.length];
            BigDecimal[] p90 = new BigDecimal[HORIZONS.length];
            BigDecimal[] flatAt = new BigDecimal[HORIZONS.length];
            BigDecimal[] repeatAt = new BigDecimal[HORIZONS.length];
            BigDecimal[] financing = new BigDecimal[HORIZONS.length];
            for (int i = 0; i < HORIZONS.length; i++) {
                int h = HORIZONS[i];
                financing[i] = ownerFinancing(c, cutoff, cutoff.plusDays(h));
                actual[i] = daily[base + h];
                p10[i] = f.days().get(h - 1).p10();
                p50[i] = f.days().get(h - 1).p50();
                p90[i] = f.days().get(h - 1).p90();
                flatAt[i] = flat[h - 1];
                repeatAt[i] = repeat[h - 1];
            }
            Map<String, Outcome> outcomes = new LinkedHashMap<>();
            outcomes.put(
                    "minimum",
                    outcome(
                            c.minimumBalance(),
                            Alert.Kind.BELOW_MINIMUM,
                            s.balance(),
                            daily,
                            base,
                            horizon,
                            f,
                            repeat,
                            lastMonthOutflow));
            outcomes.put(
                    "zero",
                    outcome(
                            Money.ZERO,
                            Alert.Kind.BELOW_ZERO,
                            s.balance(),
                            daily,
                            base,
                            horizon,
                            f,
                            repeat,
                            lastMonthOutflow));
            results.add(new CutoffResult(
                    c.seed(),
                    c.archetype(),
                    cutoff,
                    monthlyOutflow(c, cutoff),
                    actual,
                    p10,
                    p50,
                    p90,
                    flatAt,
                    repeatAt,
                    financing,
                    outcomes));
        }
        return results;
    }

    private static Outcome outcome(
            BigDecimal threshold,
            Alert.Kind kind,
            BigDecimal balance,
            BigDecimal[] daily,
            int base,
            int horizon,
            Forecast f,
            BigDecimal[] repeat,
            BigDecimal lastMonthOutflow) {
        boolean eligible = balance.compareTo(threshold) >= 0;
        int first = -1;
        for (int h = 1; h <= horizon; h++) {
            if (daily[base + h].compareTo(threshold) < 0) {
                first = h;
                break;
            }
        }
        boolean runwayAlert = f.alerts().stream().anyMatch(a -> a.kind() == kind);
        boolean repeatAlert = Arrays.stream(repeat).anyMatch(b -> b.compareTo(threshold) < 0);
        boolean cushionAlert = balance.subtract(threshold).compareTo(lastMonthOutflow) < 0;
        return new Outcome(eligible, first, runwayAlert, repeatAlert, cushionAlert);
    }

    /**
     * Net owner loans and dividends in (from, to]. These react to the cash position (a loan when the
     * overdraft is deep), so no forecast made before them can know them.
     */
    static BigDecimal ownerFinancing(SyntheticCompany c, LocalDate from, LocalDate to) {
        BigDecimal sum = Money.ZERO;
        for (BankTransaction t : c.transactions()) {
            boolean owner = t.counterparty().equals(CompanyGenerator.OWNER_LOAN)
                    || t.counterparty().equals(CompanyGenerator.OWNER_DIVIDEND);
            if (owner && t.date().isAfter(from) && !t.date().isAfter(to)) {
                sum = sum.add(t.amount());
            }
        }
        return sum;
    }

    /** Closing balance per day, index 0 = opening date. */
    static BigDecimal[] dailyBalances(SyntheticCompany c) {
        int days = (int) ChronoUnit.DAYS.between(c.openingDate(), c.end()) + 1;
        BigDecimal[] flows = new BigDecimal[days];
        Arrays.fill(flows, Money.ZERO);
        for (BankTransaction t : c.transactions()) {
            int i = (int) ChronoUnit.DAYS.between(c.openingDate(), t.date());
            if (i > 0 && i < days) {
                flows[i] = flows[i].add(t.amount());
            }
        }
        BigDecimal[] balances = new BigDecimal[days];
        BigDecimal b = c.openingBalance();
        for (int i = 0; i < days; i++) {
            b = b.add(flows[i]);
            balances[i] = b;
        }
        return balances;
    }

    private static int index(SyntheticCompany c, LocalDate date) {
        return (int) ChronoUnit.DAYS.between(c.openingDate(), date);
    }

    /** Average monthly outflow over the 12 months before the cutoff, used to compare errors across company sizes. */
    private static double monthlyOutflow(SyntheticCompany c, LocalDate cutoff) {
        BigDecimal out = Money.ZERO;
        LocalDate from = cutoff.minusDays(365);
        for (BankTransaction t : c.transactions()) {
            if (t.amount().signum() < 0 && t.date().isAfter(from) && !t.date().isAfter(cutoff)) {
                out = out.add(t.amount().negate());
            }
        }
        return out.doubleValue() / 12.0;
    }
}
