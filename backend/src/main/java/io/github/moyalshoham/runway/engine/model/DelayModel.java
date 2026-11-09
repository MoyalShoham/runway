package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Empirical distribution of payment delay (paid date minus due date, in days) per counterparty,
 * shrunk toward the distribution over all counterparties.
 *
 * <p>Sampling draws from the counterparty's own delays with probability n / (n + k), where n is its
 * number of paid documents and k is the prior strength, otherwise from the pooled delays. A new
 * customer therefore behaves like the average customer, and one with a long record like itself.
 *
 * <p>Samples are conditioned on what is already known: an invoice that is 20 days overdue today has a
 * delay of more than 20 days, so only such delays are drawn (right-censoring). When the history has
 * no delay that long, the excess is drawn from an exponential tail.
 */
public final class DelayModel {

    static final int LOOKBACK_DAYS = 730;
    static final int MAX_REJECTION_TRIES = 64;
    static final double TAIL_MEAN_DAYS = 21.0;

    private final Map<String, int[]> byCounterparty;
    private final int[] pooled;
    private final double priorStrength;

    private DelayModel(Map<String, int[]> byCounterparty, int[] pooled, double priorStrength) {
        this.byCounterparty = byCounterparty;
        this.pooled = pooled;
        this.priorStrength = priorStrength;
    }

    /** Fits on documents of {@code type} paid on or before {@code asOf}, within the lookback window. */
    public static DelayModel fit(List<Document> documents, DocumentType type, LocalDate asOf, double priorStrength) {
        Map<String, List<Integer>> lists = new HashMap<>();
        List<Integer> all = new ArrayList<>();
        LocalDate from = asOf.minusDays(LOOKBACK_DAYS);
        for (Document d : documents) {
            if (d.type() != type
                    || d.paidDate() == null
                    || d.paidDate().isAfter(asOf)
                    || d.paidDate().isBefore(from)) {
                continue;
            }
            int delay = (int) ChronoUnit.DAYS.between(d.dueDate(), d.paidDate());
            lists.computeIfAbsent(Counterparties.key(d.counterparty()), k -> new ArrayList<>())
                    .add(delay);
            all.add(delay);
        }
        Map<String, int[]> byCounterparty = new HashMap<>();
        lists.forEach((k, v) -> byCounterparty.put(k, toSortedArray(v)));
        return new DelayModel(byCounterparty, toSortedArray(all), priorStrength);
    }

    /** Number of paid documents on record for this counterparty. */
    public int observations(String counterpartyKey) {
        int[] own = byCounterparty.get(counterpartyKey);
        return own == null ? 0 : own.length;
    }

    /**
     * Draws a delay in days. The result is greater than {@code daysOverdue}, i.e. the payment lands
     * after asOf. {@code daysOverdue} is asOf minus the due date, negative when not yet due.
     */
    public int sample(String counterpartyKey, int daysOverdue, SplittableRandom rng) {
        int[] own = byCounterparty.getOrDefault(counterpartyKey, new int[0]);
        if (own.length == 0 && pooled.length == 0) {
            return Math.max(0, daysOverdue + 1);
        }
        double ownWeight = own.length / (own.length + priorStrength);
        for (int i = 0; i < MAX_REJECTION_TRIES; i++) {
            int[] source = own.length > 0 && rng.nextDouble() < ownWeight ? own : pooled;
            if (source.length == 0) {
                source = own;
            }
            int delay = source[rng.nextInt(source.length)] + rng.nextInt(-2, 3);
            if (delay > daysOverdue) {
                return delay;
            }
        }
        double excess = -TAIL_MEAN_DAYS * Math.log(1.0 - rng.nextDouble());
        return daysOverdue + 1 + (int) Math.floor(excess);
    }

    /** Average delay for display: the mixture mean of the counterparty's and the pooled delays. */
    public double meanDelay(String counterpartyKey) {
        int[] own = byCounterparty.getOrDefault(counterpartyKey, new int[0]);
        double pooledMean = Arrays.stream(pooled).average().orElse(0);
        if (own.length == 0) {
            return pooledMean;
        }
        double ownWeight = own.length / (own.length + priorStrength);
        return ownWeight * Arrays.stream(own).average().orElse(0) + (1 - ownWeight) * pooledMean;
    }

    private static int[] toSortedArray(List<Integer> values) {
        int[] a = values.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(a);
        return a;
    }
}
