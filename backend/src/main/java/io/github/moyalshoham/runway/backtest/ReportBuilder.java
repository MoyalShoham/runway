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

    static Map<String, BacktestReport.ShortfallMetrics> shortfalls(List<Backtester.CutoffResult> results) {
        Map<String, BacktestReport.ShortfallMetrics> out = new LinkedHashMap<>();
        for (String threshold : List.of("minimum", "zero")) {
            int eligible = 0;
            int real = 0;
            int warned = 0;
            int alerts = 0;
            int falseAlarms = 0;
            int warnedRepeat = 0;
            int alertsRepeat = 0;
            int falseRepeat = 0;
            List<Integer> leads = new ArrayList<>();
            for (Backtester.CutoffResult r : results) {
                Backtester.Outcome o = r.outcomes().get(threshold);
                if (!o.eligible()) {
                    continue;
                }
                eligible++;
                boolean happened = o.actualFirstBreach() > 0;
                boolean counted = o.actualFirstBreach() >= Backtester.MIN_LEAD_DAYS;
                if (counted) {
                    real++;
                    if (o.runwayAlert()) {
                        warned++;
                        leads.add(o.actualFirstBreach());
                    }
                    if (o.repeatAlert()) {
                        warnedRepeat++;
                    }
                }
                if (o.runwayAlert()) {
                    alerts++;
                    if (!happened) {
                        falseAlarms++;
                    }
                }
                if (o.repeatAlert()) {
                    alertsRepeat++;
                    if (!happened) {
                        falseRepeat++;
                    }
                }
            }
            leads.sort(Integer::compareTo);
            out.put(
                    threshold,
                    new BacktestReport.ShortfallMetrics(
                            eligible,
                            real,
                            warned,
                            alerts,
                            falseAlarms,
                            ratio(warned, real),
                            ratio(falseAlarms, alerts),
                            warnedRepeat,
                            alertsRepeat,
                            falseRepeat,
                            ratio(warnedRepeat, real),
                            ratio(falseRepeat, alertsRepeat),
                            leads.isEmpty() ? 0 : leads.get(leads.size() / 2)));
        }
        return out;
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
