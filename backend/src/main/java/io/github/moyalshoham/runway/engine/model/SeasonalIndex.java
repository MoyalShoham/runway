package io.github.moyalshoham.runway.engine.model;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multiplicative month-of-year index (1.0 = an average month), estimated as the ratio of each month
 * to a 12-month moving average so that growth is not mistaken for seasonality. Each month's ratio is
 * shrunk toward 1 by n / (n + 1), where n is the number of years observed for that month, and the
 * index needs at least 12 complete months.
 */
public final class SeasonalIndex {

    static final int MIN_MONTHS = 12;
    static final double MIN_FACTOR = 0.3;
    static final double MAX_FACTOR = 3.0;

    private final double[] factors;

    private SeasonalIndex(double[] factors) {
        this.factors = factors;
    }

    public static SeasonalIndex flat() {
        double[] f = new double[12];
        Arrays.fill(f, 1.0);
        return new SeasonalIndex(f);
    }

    /**
     * @param monthlyTotals absolute flow per month; months with no flow must be present with zero
     * @param lastCompleteMonth months after this are ignored (the current month is partial)
     */
    public static SeasonalIndex fit(Map<YearMonth, BigDecimal> monthlyTotals, YearMonth lastCompleteMonth) {
        TreeMap<YearMonth, BigDecimal> series = new TreeMap<>(monthlyTotals);
        series.tailMap(lastCompleteMonth, false).clear();
        List<YearMonth> months = new ArrayList<>(series.keySet());
        if (months.size() > 36) {
            months = months.subList(months.size() - 36, months.size());
        }
        int n = months.size();
        if (n < MIN_MONTHS) {
            return flat();
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = series.get(months.get(i)).abs().doubleValue();
        }
        double[] ratioSum = new double[12];
        int[] ratioCount = new int[12];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, Math.min(i - 6, n - 12));
            double mean = 0;
            for (int j = start; j < start + 12; j++) {
                mean += values[j];
            }
            mean /= 12;
            if (mean <= 0) {
                continue;
            }
            int m = months.get(i).getMonthValue() - 1;
            ratioSum[m] += values[i] / mean;
            ratioCount[m]++;
        }
        double[] factors = new double[12];
        double total = 0;
        for (int m = 0; m < 12; m++) {
            if (ratioCount[m] == 0) {
                factors[m] = 1.0;
            } else {
                double raw = ratioSum[m] / ratioCount[m];
                double weight = ratioCount[m] / (ratioCount[m] + 1.0);
                factors[m] = 1.0 + (raw - 1.0) * weight;
            }
            factors[m] = Math.clamp(factors[m], MIN_FACTOR, MAX_FACTOR);
            total += factors[m];
        }
        double norm = total / 12;
        for (int m = 0; m < 12; m++) {
            factors[m] /= norm;
        }
        return new SeasonalIndex(factors);
    }

    public double factor(Month month) {
        return factors[month.getValue() - 1];
    }
}
