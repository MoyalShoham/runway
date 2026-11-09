package io.github.moyalshoham.runway.backtest;

import io.github.moyalshoham.runway.engine.domain.Money;
import io.github.moyalshoham.runway.sim.Archetype;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates per-cutoff results into a {@link BacktestReport}. */
final class ReportBuilder {

    private ReportBuilder() {}

    static List<BacktestReport.HorizonMetrics> horizons(List<Backtester.CutoffResult> results) {
        List<BacktestReport.HorizonMetrics> metrics = new ArrayList<>();
        for (int i = 0; i < Backtester.HORIZONS.length; i++) {
            final int k = i;
            List<BigDecimal> runway = new ArrayList<>();
            List<BigDecimal> flat = new ArrayList<>();
            List<BigDecimal> repeat = new ArrayList<>();
            List<BigDecimal> signed = new ArrayList<>();
            List<BigDecimal> runwayEx = new ArrayList<>();
            List<BigDecimal> flatEx = new ArrayList<>();
            List<BigDecimal> repeatEx = new ArrayList<>();
            List<BigDecimal> signedEx = new ArrayList<>();
            int coveredEx = 0;
            double relRunway = 0;
            double relFlat = 0;
            double relRepeat = 0;
            int covered = 0;
            for (Backtester.CutoffResult r : results) {
                BigDecimal actual = r.actual()[k];
                BigDecimal e = r.p50()[k].subtract(actual).abs();
                BigDecimal ef = r.flat()[k].subtract(actual).abs();
                BigDecimal er = r.repeat()[k].subtract(actual).abs();
                runway.add(e);
                signed.add(r.p50()[k].subtract(actual));
                flat.add(ef);
                repeat.add(er);
                double scale = Math.max(1.0, r.monthlyOutflow());
                relRunway += e.doubleValue() / scale;
                relFlat += ef.doubleValue() / scale;
                relRepeat += er.doubleValue() / scale;
                if (actual.compareTo(r.p10()[k]) >= 0 && actual.compareTo(r.p90()[k]) <= 0) {
                    covered++;
                }
                BigDecimal business = actual.subtract(r.ownerFinancing()[k]);
                runwayEx.add(r.p50()[k].subtract(business).abs());
                signedEx.add(r.p50()[k].subtract(business));
                flatEx.add(r.flat()[k].subtract(business).abs());
                repeatEx.add(r.repeat()[k].subtract(business).abs());
                if (business.compareTo(r.p10()[k]) >= 0 && business.compareTo(r.p90()[k]) <= 0) {
                    coveredEx++;
                }
            }
            int n = results.size();
            metrics.add(new BacktestReport.HorizonMetrics(
                    Backtester.HORIZONS[k],
                    n,
                    mean(runway),
                    mean(flat),
                    mean(repeat),
                    Money.median(runway),
                    mean(signed),
                    relRunway / n,
                    relFlat / n,
                    relRepeat / n,
                    covered / (double) n,
                    mean(runwayEx),
                    mean(flatEx),
                    mean(repeatEx),
                    mean(signedEx),
                    coveredEx / (double) n));
        }
        return metrics;
    }

    static final String[] METHODS = {"runway", "lastThirtyDays", "cushion"};

    static Map<String, BacktestReport.ShortfallMetrics> shortfalls(List<Backtester.CutoffResult> results) {
        Map<String, BacktestReport.ShortfallMetrics> out = new LinkedHashMap<>();
        for (String threshold : List.of("minimum", "zero")) {
            int eligible = 0;
            int real = 0;
            // Event = (company, day the balance first fell below the threshold); cutoffs in date order.
            Map<String, List<Backtester.CutoffResult>> events = new LinkedHashMap<>();
            for (Backtester.CutoffResult r : results) {
                Backtester.Outcome o = r.outcomes().get(threshold);
                if (!o.eligible()) {
                    continue;
                }
                eligible++;
                if (o.actualFirstBreach() >= Backtester.MIN_LEAD_DAYS) {
                    real++;
                    String event = r.seed() + "@" + r.cutoff().plusDays(o.actualFirstBreach());
                    events.computeIfAbsent(event, k -> new ArrayList<>()).add(r);
                }
            }
            Map<String, BacktestReport.Warnings> methods = new LinkedHashMap<>();
            for (String method : METHODS) {
                int warned = 0;
                int alerts = 0;
                int falseAlarms = 0;
                for (Backtester.CutoffResult r : results) {
                    Backtester.Outcome o = r.outcomes().get(threshold);
                    if (!o.eligible()) {
                        continue;
                    }
                    boolean alert = alerted(o, method);
                    if (o.actualFirstBreach() >= Backtester.MIN_LEAD_DAYS && alert) {
                        warned++;
                    }
                    if (alert) {
                        alerts++;
                        if (o.actualFirstBreach() < 0) {
                            falseAlarms++;
                        }
                    }
                }
                int any = 0;
                int first = 0;
                List<Integer> leads = new ArrayList<>();
                for (List<Backtester.CutoffResult> cutoffs : events.values()) {
                    cutoffs.sort(java.util.Comparator.comparing(Backtester.CutoffResult::cutoff));
                    Backtester.CutoffResult earliest = cutoffs.getFirst();
                    if (alerted(earliest.outcomes().get(threshold), method)) {
                        first++;
                    }
                    for (Backtester.CutoffResult c : cutoffs) {
                        Backtester.Outcome o = c.outcomes().get(threshold);
                        if (alerted(o, method)) {
                            any++;
                            leads.add(o.actualFirstBreach());
                            break;
                        }
                    }
                }
                leads.sort(Integer::compareTo);
                methods.put(
                        method,
                        new BacktestReport.Warnings(
                                warned,
                                alerts,
                                falseAlarms,
                                ratio(warned, real),
                                ratio(falseAlarms, alerts),
                                any,
                                first,
                                leads.isEmpty() ? 0 : leads.get((leads.size() - 1) / 2)));
            }
            out.put(threshold, new BacktestReport.ShortfallMetrics(eligible, real, events.size(), methods));
        }
        return out;
    }

    static boolean alerted(Backtester.Outcome o, String method) {
        return switch (method) {
            case "runway" -> o.runwayAlert();
            case "lastThirtyDays" -> o.repeatAlert();
            case "cushion" -> o.cushionAlert();
            default -> throw new IllegalArgumentException(method);
        };
    }

    /**
     * 95% intervals for the headline numbers, resampling whole companies with replacement (cutoffs of
     * one company are not independent, so resampling cutoffs would understate the uncertainty).
     */
    static Map<String, BacktestReport.Interval> confidence(
            List<Backtester.CutoffResult> results, int rounds, long seed) {
        Map<Long, List<Backtester.CutoffResult>> byCompany = new LinkedHashMap<>();
        for (Backtester.CutoffResult r : results) {
            byCompany.computeIfAbsent(r.seed(), k -> new ArrayList<>()).add(r);
        }
        List<List<Backtester.CutoffResult>> companies = new ArrayList<>(byCompany.values());
        java.util.SplittableRandom rng = new java.util.SplittableRandom(seed);
        Map<String, List<Double>> samples = new LinkedHashMap<>();
        for (int b = 0; b < rounds; b++) {
            List<Backtester.CutoffResult> sample = new ArrayList<>();
            for (int i = 0; i < companies.size(); i++) {
                sample.addAll(companies.get(rng.nextInt(companies.size())));
            }
            List<BacktestReport.HorizonMetrics> h = horizons(sample);
            BacktestReport.ShortfallMetrics m = shortfalls(sample).get("minimum");
            BacktestReport.Warnings w = m.methods().get("runway");
            add(
                    samples,
                    "mae30ReductionVsFlat",
                    1 - h.get(1).maeRunway().doubleValue() / h.get(1).maeFlat().doubleValue());
            add(
                    samples,
                    "mae90ReductionVsFlat",
                    1 - h.get(2).maeRunway().doubleValue() / h.get(2).maeFlat().doubleValue());
            add(samples, "coverage90", h.get(2).coverage());
            add(samples, "minimumRecall", w.recall());
            add(samples, "minimumFalseAlarmShare", w.falseAlarmShare());
            add(samples, "minimumEventsWarnedFirst", ratio(w.eventsWarnedFirst(), m.events()));
        }
        Map<String, BacktestReport.Interval> out = new LinkedHashMap<>();
        samples.forEach((k, v) -> {
            List<Double> sorted = v.stream().sorted().toList();
            out.put(
                    k,
                    new BacktestReport.Interval(
                            sorted.get((int) Math.floor(0.025 * (sorted.size() - 1))),
                            sorted.get((int) Math.ceil(0.975 * (sorted.size() - 1)))));
        });
        return out;
    }

    private static void add(Map<String, List<Double>> samples, String key, double value) {
        samples.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    static Map<String, Map<String, BigDecimal>> byArchetype(List<Backtester.CutoffResult> results) {
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        int k = 1; // 30 days
        for (Archetype a : Archetype.values()) {
            List<BigDecimal> runway = new ArrayList<>();
            List<BigDecimal> flat = new ArrayList<>();
            List<BigDecimal> repeat = new ArrayList<>();
            for (Backtester.CutoffResult r : results) {
                if (r.archetype() == a) {
                    runway.add(r.p50()[k].subtract(r.actual()[k]).abs());
                    flat.add(r.flat()[k].subtract(r.actual()[k]).abs());
                    repeat.add(r.repeat()[k].subtract(r.actual()[k]).abs());
                }
            }
            if (!runway.isEmpty()) {
                Map<String, BigDecimal> m = new LinkedHashMap<>();
                m.put("runway", mean(runway));
                m.put("flat", mean(flat));
                m.put("repeat", mean(repeat));
                out.put(a.name(), m);
            }
        }
        return out;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        return Money.sum(values).divide(BigDecimal.valueOf(Math.max(1, values.size())), Money.SCALE, Money.ROUNDING);
    }

    private static double ratio(int a, int b) {
        return b == 0 ? 0 : a / (double) b;
    }
}
