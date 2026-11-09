package io.github.moyalshoham.runway.engine.forecast;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns simulated balance paths into alerts.
 *
 * <p>An alert fires when at least {@code alertProbability} of the paths dip below the threshold at
 * some point in the horizon. Its date is the day by which that share of paths has dipped, and its
 * shortfall is the extra cash that, added today, would bring the share under {@code alertProbability}.
 */
final class AlertDetector {

    static final double LIKELY = 0.75;
    private static final int MAX_DRIVERS = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private AlertDetector() {}

    /** Share of paths whose lowest balance is below {@code threshold}. */
    static double breachProbability(BigDecimal[] pathMinimums, BigDecimal threshold) {
        long below = Arrays.stream(pathMinimums)
                .filter(m -> m.compareTo(threshold) < 0)
                .count();
        return below / (double) pathMinimums.length;
    }

    static Optional<Alert> detect(
            Alert.Kind kind,
            BigDecimal threshold,
            double alertProbability,
            LocalDate asOf,
            BigDecimal[][] balances,
            List<DailyPoint> days,
            List<ScheduleItem> schedule) {
        int paths = balances.length;
        int horizon = balances[0].length;
        BigDecimal[] minimums = new BigDecimal[paths];
        int[] firstBreach = new int[paths];
        for (int p = 0; p < paths; p++) {
            BigDecimal min = balances[p][0];
            firstBreach[p] = Integer.MAX_VALUE;
            for (int h = 0; h < horizon; h++) {
                BigDecimal b = balances[p][h];
                if (b.compareTo(min) < 0) {
                    min = b;
                }
                if (firstBreach[p] == Integer.MAX_VALUE && b.compareTo(threshold) < 0) {
                    firstBreach[p] = h + 1;
                }
            }
            minimums[p] = min;
        }
        double probability = breachProbability(minimums, threshold);
        if (probability < alertProbability) {
            return Optional.empty();
        }
        int rank = (int) Math.ceil(alertProbability * paths);
        int[] sortedBreach = firstBreach.clone();
        Arrays.sort(sortedBreach);
        LocalDate date = asOf.plusDays(sortedBreach[rank - 1]);

        BigDecimal[] deficits = new BigDecimal[paths];
        for (int p = 0; p < paths; p++) {
            deficits[p] = threshold.subtract(minimums[p]);
        }
        Arrays.sort(deficits, Comparator.reverseOrder());
        // Adding X today lifts every path by X; fewer than rank paths stay below once X >= deficits[rank-1].
        BigDecimal shortfall = Money.round(deficits[rank - 1].max(Money.ZERO));

        DailyPoint lowest =
                days.stream().min(Comparator.comparing(DailyPoint::p50)).orElseThrow();
        Alert.Severity severity = probability >= LIKELY ? Alert.Severity.LIKELY : Alert.Severity.POSSIBLE;
        List<Alert.Driver> drivers = drivers(asOf, date, schedule);
        String what = kind == Alert.Kind.BELOW_ZERO ? "below zero" : "below your minimum of " + format(threshold);
        String message = String.format(
                Locale.ENGLISH,
                "The balance %s fall %s by %s (%d%% of simulated paths). About %s more cash would keep that risk under %d%%.",
                severity == Alert.Severity.LIKELY ? "is likely to" : "could",
                what,
                date.format(DATE),
                Math.round(probability * 100),
                format(shortfall),
                Math.round(alertProbability * 100));
        return Optional.of(new Alert(
                kind,
                severity,
                threshold,
                probability,
                date,
                shortfall,
                lowest.date(),
                lowest.p50(),
                drivers,
                message));
    }

    /**
     * The flows that push the balance down before the alert date: the largest outflows due by then,
     * and receivables expected only after it (money that would help if it arrived in time).
     */
    static List<Alert.Driver> drivers(LocalDate asOf, LocalDate alertDate, List<ScheduleItem> schedule) {
        List<Alert.Driver> drivers = new ArrayList<>();
        BigDecimal irregular = Money.ZERO;
        for (ScheduleItem item : schedule) {
            boolean beforeAlert = !item.date().isAfter(alertDate);
            if (item.component() == Component.IRREGULAR) {
                if (beforeAlert) {
                    irregular = irregular.add(Money.times(item.amount(), item.probability()));
                }
                continue;
            }
            if (beforeAlert && item.amount().signum() < 0) {
                drivers.add(new Alert.Driver(
                        item.label(), item.component(), item.date(), item.amount(), "due before the shortfall"));
            } else if (!beforeAlert
                    && item.amount().signum() > 0
                    && item.component() == Component.OPEN_INVOICES
                    && item.date().isBefore(alertDate.plusDays(30))) {
                drivers.add(new Alert.Driver(
                        item.label(),
                        item.component(),
                        item.date(),
                        item.amount(),
                        "expected only after the shortfall"));
            }
        }
        if (irregular.signum() < 0) {
            drivers.add(new Alert.Driver(
                    "Irregular spending",
                    Component.IRREGULAR,
                    alertDate,
                    irregular,
                    "net irregular flows until the shortfall"));
        }
        drivers.sort(Comparator.comparing((Alert.Driver d) -> d.amount().abs()).reversed());
        return drivers.subList(0, Math.min(MAX_DRIVERS, drivers.size()));
    }

    static String format(BigDecimal amount) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "ILS " + f.format(amount);
    }
}
