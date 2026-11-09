package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Irregular flows: everything that is not a document payment, a recurring item or VAT (card
 * purchases, walk-in sales, one-off spending).
 *
 * <p>Simulated with a weekly block bootstrap over the last {@code windowDays}: each future week is a
 * copy of a randomly chosen past week, which keeps the day-of-week shape and the lumpiness of real
 * spending. Inflows and outflows are de-seasonalised by the month they came from and re-seasonalised
 * for the month they are projected into.
 */
public final class BaselineModel {

    private final LocalDate windowStart;
    private final BigDecimal[] inflow;
    private final BigDecimal[] outflow;
    private final SeasonalIndex inSeason;
    private final SeasonalIndex outSeason;
    private final int weeks;

    private BaselineModel(
            LocalDate windowStart,
            BigDecimal[] inflow,
            BigDecimal[] outflow,
            SeasonalIndex inSeason,
            SeasonalIndex outSeason) {
        this.windowStart = windowStart;
        this.inflow = inflow;
        this.outflow = outflow;
        this.inSeason = inSeason;
        this.outSeason = outSeason;
        this.weeks = inflow.length / 7;
    }

    /**
     * @param residual irregular transactions on or before {@code asOf}
     * @param historyStart first day covered by the bank data; days before it count as unknown, not zero
     */
    public static BaselineModel fit(
            List<BankTransaction> residual, LocalDate historyStart, LocalDate asOf, int windowDays) {
        long available = ChronoUnit.DAYS.between(historyStart, asOf) + 1;
        int days = (int) Math.max(7, Math.min(windowDays, (available / 7) * 7));
        LocalDate start = asOf.minusDays(days - 1L);
        BigDecimal[] in = filled(days);
        BigDecimal[] out = filled(days);
        TreeMap<YearMonth, BigDecimal> inMonthly = new TreeMap<>();
        TreeMap<YearMonth, BigDecimal> outMonthly = new TreeMap<>();
        for (YearMonth m = YearMonth.from(historyStart); !m.isAfter(YearMonth.from(asOf)); m = m.plusMonths(1)) {
            inMonthly.put(m, Money.ZERO);
            outMonthly.put(m, Money.ZERO);
        }
        for (BankTransaction t : residual) {
            if (t.date().isAfter(asOf) || t.date().isBefore(historyStart)) {
                continue;
            }
            YearMonth m = YearMonth.from(t.date());
            if (t.isInflow()) {
                inMonthly.merge(m, t.amount(), BigDecimal::add);
            } else {
                outMonthly.merge(m, t.amount().negate(), BigDecimal::add);
            }
            if (!t.date().isBefore(start)) {
                int i = (int) ChronoUnit.DAYS.between(start, t.date());
                if (t.isInflow()) {
                    in[i] = in[i].add(t.amount());
                } else {
                    out[i] = out[i].add(t.amount());
                }
            }
        }
        // The first month is usually partial, so it is dropped from the seasonal fit.
        inMonthly.pollFirstEntry();
        outMonthly.pollFirstEntry();
        YearMonth lastComplete = YearMonth.from(asOf).minusMonths(1);
        return new BaselineModel(
                start,
                in,
                out,
                SeasonalIndex.fit(inMonthly, lastComplete),
                SeasonalIndex.fit(outMonthly, lastComplete));
    }

    /** One simulated path of daily irregular flows for days asOf+1 .. asOf+horizon. */
    public BigDecimal[] sample(LocalDate asOf, int horizonDays, SplittableRandom rng) {
        BigDecimal[] flows = filled(horizonDays);
        int offset = (int) (ChronoUnit.DAYS.between(windowStart, asOf.plusDays(1)) % 7);
        for (int blockStart = 0; blockStart < horizonDays; blockStart += 7) {
            int week = rng.nextInt(weeks);
            for (int k = 0; k < 7 && blockStart + k < horizonDays; k++) {
                int target = blockStart + k;
                int source = week * 7 + Math.floorMod(k + offset, 7);
                flows[target] = projected(source, asOf.plusDays(target + 1L));
            }
        }
        return flows;
    }

    /** Mean irregular net flow for a future day, using the same seasonal scaling as {@link #sample}. */
    public BigDecimal expected(LocalDate day) {
        BigDecimal total = Money.ZERO;
        for (int s = 0; s < inflow.length; s++) {
            total = total.add(projected(s, day));
        }
        return total.divide(BigDecimal.valueOf(inflow.length), Money.SCALE, Money.ROUNDING);
    }

    private BigDecimal projected(int source, LocalDate target) {
        LocalDate sourceDay = windowStart.plusDays(source);
        double inScale = inSeason.factor(target.getMonth()) / inSeason.factor(sourceDay.getMonth());
        double outScale = outSeason.factor(target.getMonth()) / outSeason.factor(sourceDay.getMonth());
        BigDecimal in = inflow[source].signum() == 0 ? Money.ZERO : Money.times(inflow[source], inScale);
        BigDecimal out = outflow[source].signum() == 0 ? Money.ZERO : Money.times(outflow[source], outScale);
        return in.add(out);
    }

    private static BigDecimal[] filled(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, Money.ZERO);
        return a;
    }
}
