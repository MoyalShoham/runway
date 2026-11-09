package io.github.moyalshoham.runway.engine.recurring;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finds recurring flows (rent, salaries, subscriptions, retainers) in a series of occurrences that
 * share a counterparty.
 *
 * <p>For each series it tries every {@link Cadence} on the most recent occurrences and scores the
 * share of intervals that land within the cadence's tolerance. One skipped period counts as half a
 * match, so a missed month does not hide a monthly rent. If the whole series does not fit (a vendor
 * with a subscription and occasional purchases), it is split into amount clusters and each cluster is
 * tried on its own.
 */
public final class RecurringDetector {

    /** Minimum share of matching intervals for a series to count as recurring. */
    public static final double MIN_REGULARITY = 0.8;
    /** Maximum median relative amount deviation for a recurring flow. */
    public static final double MAX_AMOUNT_DEVIATION = 0.35;
    /** Only this many recent occurrences are used to fit the cadence, so old behaviour cannot block detection. */
    static final int FIT_WINDOW = 12;
    /** Amounts further apart than this ratio go to different clusters. */
    static final double CLUSTER_RATIO = 1.3;

    /** A candidate series: one counterparty and direction. */
    public record Series(String key, int sign, List<Occurrence> occurrences) {
        public Series {
            occurrences = List.copyOf(occurrences);
        }
    }

    /** A detected pattern and the {@link Occurrence#sources()} of every occurrence it explains. */
    public record Detection(RecurringPattern pattern, List<Integer> sources) {
        public Detection {
            sources = List.copyOf(sources);
        }
    }

    public List<Detection> detect(List<Series> series, LocalDate asOf) {
        List<Detection> detections = new ArrayList<>();
        for (Series s : series) {
            detections.addAll(detect(s, asOf));
        }
        detections.sort(Comparator.comparing(d -> d.pattern().key()));
        return detections;
    }

    /** All recurring patterns in one series: either the whole series, or one per amount cluster. */
    public List<Detection> detect(Series series, LocalDate asOf) {
        List<Occurrence> daily = mergeSameDay(series.occurrences());
        Optional<RecurringPattern> whole = fit(series.key(), series.sign(), daily, asOf);
        if (whole.isPresent()) {
            return List.of(new Detection(whole.get(), sourcesOf(daily)));
        }
        List<List<Occurrence>> clusters = clusterByAmount(series.occurrences());
        if (clusters.size() < 2) {
            return List.of();
        }
        List<Detection> found = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            List<Occurrence> cluster = mergeSameDay(clusters.get(i));
            fit(series.key() + "#" + (i + 1), series.sign(), cluster, asOf)
                    .ifPresent(p -> found.add(new Detection(p, sourcesOf(cluster))));
        }
        return found;
    }

    private static List<Integer> sourcesOf(List<Occurrence> occ) {
        return occ.stream().flatMap(o -> o.sources().stream()).toList();
    }

    private Optional<RecurringPattern> fit(String key, int sign, List<Occurrence> all, LocalDate asOf) {
        if (all.size() < 2) {
            return Optional.empty();
        }
        List<Occurrence> window = all.subList(Math.max(0, all.size() - FIT_WINDOW), all.size());
        Cadence best = null;
        double bestScore = -1;
        for (Cadence c : Cadence.values()) {
            double score = regularity(window, c);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null || bestScore < MIN_REGULARITY) {
            return Optional.empty();
        }
        List<Double> deviations = relativeAmountDeviations(window);
        double typicalDeviation = medianAbs(deviations);
        if (typicalDeviation > MAX_AMOUNT_DEVIATION) {
            return Optional.empty();
        }
        Occurrence first = all.getFirst();
        Occurrence last = all.getLast();
        int anchor = best.isMonthBased() ? anchorOf(window) : 0;
        List<Integer> dateResiduals = dateResiduals(window, best, anchor);
        BigDecimal amount = Money.median(lastAmounts(window, 3));
        long sinceLast = ChronoUnit.DAYS.between(last.date(), asOf);
        boolean active = sinceLast <= best.nominalDays() * 1.5 + best.toleranceDays();
        double history = Math.min(1.0, window.size() / 6.0);
        double confidence = bestScore * history * (1.0 - Math.min(0.5, typicalDeviation));
        return Optional.of(new RecurringPattern(
                key,
                mostCommonLabel(all),
                sign,
                best,
                anchor,
                amount,
                deviations.isEmpty() ? List.of(0.0) : deviations,
                dateResiduals.isEmpty() ? List.of(0) : dateResiduals,
                first.date(),
                last.date(),
                all.size(),
                bestScore,
                confidence,
                active,
                amountChange(window)));
    }

    /** Share of intervals that match {@code c}; a gap of two periods counts half. Zero if not enough data. */
    static double regularity(List<Occurrence> occ, Cadence c) {
        if (occ.size() < c.minOccurrences()) {
            return 0;
        }
        int intervals = occ.size() - 1;
        int hits = 0;
        int skips = 0;
        double nominal = c.nominalDays();
        for (int i = 1; i < occ.size(); i++) {
            long gap = ChronoUnit.DAYS.between(occ.get(i - 1).date(), occ.get(i).date());
            if (Math.abs(gap - nominal) <= c.toleranceDays()) {
                hits++;
            } else if (Math.abs(gap - 2 * nominal) <= 1.5 * c.toleranceDays()) {
                skips++;
            }
        }
        if (skips * 3 > intervals) {
            return 0;
        }
        return (hits + 0.5 * skips) / intervals;
    }

    /** Median anchor of the occurrences, robust to weekend shifts into the next month. */
    static int anchorOf(List<Occurrence> occ) {
        List<Integer> anchors = occ.stream()
                .map(o -> RecurringPattern.anchorOf(o.date()))
                .sorted()
                .toList();
        return anchors.get(anchors.size() / 2);
    }

    static List<Integer> dateResiduals(List<Occurrence> occ, Cadence c, int anchor) {
        List<Integer> residuals = new ArrayList<>();
        if (c.isMonthBased()) {
            for (Occurrence o : occ) {
                YearMonth m = RecurringPattern.nominalMonth(o.date(), anchor);
                residuals.add((int) ChronoUnit.DAYS.between(RecurringPattern.anchorDate(m, anchor), o.date()));
            }
        } else {
            for (int i = 1; i < occ.size(); i++) {
                long gap = ChronoUnit.DAYS.between(
                        occ.get(i - 1).date(), occ.get(i).date());
                if (Math.abs(gap - c.days()) <= c.toleranceDays()) {
                    residuals.add((int) (gap - c.days()));
                }
            }
        }
        return residuals;
    }

    /** For each occurrence after the first: amount / median(previous up to 3) - 1. */
    static List<Double> relativeAmountDeviations(List<Occurrence> occ) {
        List<Double> deviations = new ArrayList<>();
        for (int i = 1; i < occ.size(); i++) {
            List<BigDecimal> previous = new ArrayList<>();
            for (int j = Math.max(0, i - 3); j < i; j++) {
                previous.add(occ.get(j).amount());
            }
            BigDecimal predicted = Money.median(previous);
            if (predicted.signum() == 0) {
                continue;
            }
            double ratio =
                    occ.get(i).amount().divide(predicted, MathContext.DECIMAL64).doubleValue();
            deviations.add(ratio - 1.0);
        }
        return deviations;
    }

    static AmountChange amountChange(List<Occurrence> occ) {
        if (occ.size() < 6) {
            return null;
        }
        List<BigDecimal> recent = lastAmounts(occ, 3);
        List<BigDecimal> before = occ.subList(occ.size() - 6, occ.size() - 3).stream()
                .map(Occurrence::amount)
                .toList();
        BigDecimal newLevel = Money.median(recent);
        BigDecimal oldLevel = Money.median(before);
        if (oldLevel.signum() == 0 || !tight(recent, newLevel) || !tight(before, oldLevel)) {
            return null;
        }
        double change = newLevel.divide(oldLevel, MathContext.DECIMAL64).doubleValue() - 1.0;
        if (Math.abs(change) < 0.015) {
            return null;
        }
        LocalDate since = occ.get(occ.size() - 3).date();
        for (int i = occ.size() - 3; i < occ.size(); i++) {
            if (occ.get(i).amount().compareTo(newLevel) == 0) {
                since = occ.get(i).date();
                break;
            }
        }
        return new AmountChange(oldLevel, newLevel, since);
    }

    private static boolean tight(List<BigDecimal> values, BigDecimal level) {
        for (BigDecimal v : values) {
            double ratio = v.divide(level, MathContext.DECIMAL64).doubleValue();
            if (Math.abs(ratio - 1.0) > 0.01) {
                return false;
            }
        }
        return true;
    }

    static List<List<Occurrence>> clusterByAmount(List<Occurrence> occ) {
        List<Occurrence> byAmount =
                occ.stream().sorted(Comparator.comparing(Occurrence::amount)).toList();
        List<List<Occurrence>> clusters = new ArrayList<>();
        List<Occurrence> current = new ArrayList<>();
        for (Occurrence o : byAmount) {
            if (!current.isEmpty()) {
                BigDecimal previous = current.getLast().amount();
                boolean jump = previous.signum() == 0
                        || o.amount().divide(previous, MathContext.DECIMAL64).doubleValue() > CLUSTER_RATIO;
                if (jump) {
                    clusters.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(o);
        }
        if (!current.isEmpty()) {
            clusters.add(current);
        }
        for (List<Occurrence> cluster : clusters) {
            cluster.sort(Comparator.comparing(Occurrence::date));
        }
        return clusters;
    }

    /** Several payments to the same party on the same day count as one occurrence (e.g. payroll). */
    static List<Occurrence> mergeSameDay(List<Occurrence> occ) {
        Map<LocalDate, List<Occurrence>> byDate = occ.stream()
                .sorted(Comparator.comparing(Occurrence::date))
                .collect(Collectors.groupingBy(Occurrence::date, LinkedHashMap::new, Collectors.toList()));
        List<Occurrence> merged = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Occurrence>> e : byDate.entrySet()) {
            List<Occurrence> same = e.getValue();
            if (same.size() == 1) {
                merged.add(same.getFirst());
            } else {
                BigDecimal total =
                        Money.sum(same.stream().map(Occurrence::amount).toList());
                List<Integer> sources =
                        same.stream().flatMap(o -> o.sources().stream()).toList();
                merged.add(new Occurrence(e.getKey(), total, same.getFirst().label(), sources));
            }
        }
        return merged;
    }

    private static List<BigDecimal> lastAmounts(List<Occurrence> occ, int n) {
        return occ.subList(Math.max(0, occ.size() - n), occ.size()).stream()
                .map(Occurrence::amount)
                .toList();
    }

    private static double medianAbs(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> abs = values.stream().map(Math::abs).sorted().toList();
        int n = abs.size();
        return n % 2 == 1 ? abs.get(n / 2) : (abs.get(n / 2 - 1) + abs.get(n / 2)) / 2;
    }

    private static String mostCommonLabel(List<Occurrence> occ) {
        Map<String, Long> counts =
                occ.stream().collect(Collectors.groupingBy(Occurrence::label, Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse("");
    }
}
