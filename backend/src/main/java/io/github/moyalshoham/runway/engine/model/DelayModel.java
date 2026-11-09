package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Distribution of payment delay (paid date minus due date, in days) per counterparty, estimated with
 * Kaplan-Meier and shrunk toward the distribution over all counterparties.
 *
 * <p>Why Kaplan-Meier: an invoice that is still open on asOf is information too ("its delay is more
 * than its current days overdue"). Learning only from paid invoices keeps the fast payers and drops
 * the slow ones, which makes every forecast optimistic. Open documents enter the estimate as
 * right-censored observations.
 *
 * <p>Sampling draws from the counterparty's own curve with probability n / (n + k), n being its number
 * of observations and k the prior strength, otherwise from the pooled curve. Draws are conditioned on
 * what is known: an invoice 20 days overdue today has a delay of more than 20 days. Probability mass
 * past the longest observed payment means "not paid within any delay seen so far"; such draws return
 * {@link #NOT_PAID}.
 */
public final class DelayModel {

    /** Returned by {@link #sample} when the draw falls beyond every delay seen in the history. */
    public static final int NOT_PAID = Integer.MAX_VALUE;

    static final int LOOKBACK_DAYS = 730;

    private final Map<String, Curve> byCounterparty;
    private final Curve pooled;
    private final double priorStrength;

    private DelayModel(Map<String, Curve> byCounterparty, Curve pooled, double priorStrength) {
        this.byCounterparty = byCounterparty;
        this.pooled = pooled;
        this.priorStrength = priorStrength;
    }

    /**
     * Fits on documents of {@code type} due within the lookback window. Paid documents (paid on or
     * before asOf) are events; open documents are censored at asOf.
     */
    public static DelayModel fit(List<Document> documents, DocumentType type, LocalDate asOf, double priorStrength) {
        Map<String, List<int[]>> lists = new HashMap<>();
        List<int[]> all = new ArrayList<>();
        LocalDate from = asOf.minusDays(LOOKBACK_DAYS);
        for (Document d : documents) {
            if (d.type() != type || d.dueDate().isBefore(from) || d.issueDate().isAfter(asOf)) {
                continue;
            }
            boolean paid = d.paidDate() != null && !d.paidDate().isAfter(asOf);
            LocalDate end = paid ? d.paidDate() : asOf;
            int[] obs = {(int) ChronoUnit.DAYS.between(d.dueDate(), end), paid ? 1 : 0};
            lists.computeIfAbsent(Counterparties.key(d.counterparty()), k -> new ArrayList<>())
                    .add(obs);
            all.add(obs);
        }
        Map<String, Curve> byCounterparty = new HashMap<>();
        lists.forEach((k, v) -> byCounterparty.put(k, Curve.of(v)));
        return new DelayModel(byCounterparty, Curve.of(all), priorStrength);
    }

    /** Number of documents (paid or open) on record for this counterparty. */
    public int observations(String counterpartyKey) {
        Curve own = byCounterparty.get(counterpartyKey);
        return own == null ? 0 : own.observations;
    }

    /**
     * Draws a delay in days, greater than {@code daysOverdue} (the payment lands after asOf), or
     * {@link #NOT_PAID}. {@code daysOverdue} is asOf minus the due date, negative when not yet due.
     */
    public int sample(String counterpartyKey, int daysOverdue, SplittableRandom rng) {
        Curve own = byCounterparty.get(counterpartyKey);
        int n = own == null ? 0 : own.observations;
        Curve curve = n > 0 && rng.nextDouble() < n / (n + priorStrength) ? own : pooled;
        if (curve.isEmpty()) {
            // A curve with no payment events (e.g. a customer whose only invoices are still open)
            // says nothing about when payments happen; use the other one.
            curve = curve == own ? pooled : own;
        }
        if (curve == null || curve.isEmpty()) {
            return Math.max(0, daysOverdue + 1);
        }
        int delay = curve.sampleAfter(daysOverdue, rng);
        if (delay == NOT_PAID) {
            return NOT_PAID;
        }
        delay += rng.nextInt(-2, 3);
        return Math.max(delay, daysOverdue + 1);
    }

    /**
     * Median delay for display: the counterparty's own curve once it has at least as many
     * observations as the prior strength, otherwise the pooled curve; 0 without history.
     */
    public int typicalDelay(String counterpartyKey) {
        Curve own = byCounterparty.get(counterpartyKey);
        Curve c = own != null && own.observations >= priorStrength && !own.isEmpty() ? own : pooled;
        return c.isEmpty() ? 0 : c.quantile(0.5);
    }

    /**
     * Kaplan-Meier estimate as a step function: {@code times[i]} is the i-th distinct event time and
     * {@code cdf[i]} the probability of being paid by then. {@code cdf} stays below 1 when the longest
     * observations are censored.
     */
    static final class Curve {
        final int[] times;
        final double[] cdf;
        final int observations;

        private Curve(int[] times, double[] cdf, int observations) {
            this.times = times;
            this.cdf = cdf;
            this.observations = observations;
        }

        static Curve of(List<int[]> observations) {
            List<int[]> sorted = new ArrayList<>(observations);
            // Events before censorings at the same time, the usual Kaplan-Meier convention.
            sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
            List<Integer> times = new ArrayList<>();
            List<Double> cdf = new ArrayList<>();
            int atRisk = sorted.size();
            double survival = 1.0;
            int i = 0;
            while (i < sorted.size()) {
                int t = sorted.get(i)[0];
                int events = 0;
                int leaving = 0;
                while (i < sorted.size() && sorted.get(i)[0] == t) {
                    events += sorted.get(i)[1];
                    leaving++;
                    i++;
                }
                if (events > 0) {
                    survival *= 1.0 - events / (double) atRisk;
                    times.add(t);
                    cdf.add(1.0 - survival);
                }
                atRisk -= leaving;
            }
            return new Curve(
                    times.stream().mapToInt(Integer::intValue).toArray(),
                    cdf.stream().mapToDouble(Double::doubleValue).toArray(),
                    observations.size());
        }

        boolean isEmpty() {
            return times.length == 0;
        }

        /** Probability of being paid by day {@code t}. */
        double cdfAt(int t) {
            double p = 0;
            for (int i = 0; i < times.length && times[i] <= t; i++) {
                p = cdf[i];
            }
            return p;
        }

        /** Draw from the curve conditioned on delay > {@code after}. */
        int sampleAfter(int after, SplittableRandom rng) {
            double base = cdfAt(after);
            double remaining = 1.0 - base;
            if (remaining <= 1e-12) {
                return NOT_PAID;
            }
            double target = base + rng.nextDouble() * remaining;
            for (int i = 0; i < times.length; i++) {
                if (times[i] > after && cdf[i] >= target) {
                    return times[i];
                }
            }
            return NOT_PAID;
        }

        int quantile(double q) {
            for (int i = 0; i < times.length; i++) {
                if (cdf[i] >= q) {
                    return times[i];
                }
            }
            return times[times.length - 1];
        }
    }
}
