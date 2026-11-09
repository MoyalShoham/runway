package io.github.moyalshoham.runway.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.model.ForecastSettings;
import io.github.moyalshoham.runway.sim.Archetype;
import io.github.moyalshoham.runway.sim.SyntheticCompany;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exact expected values for the metrics behind the README's numbers. */
class ReportBuilderTest {

    private static BigDecimal[] three(String a, String b, String c) {
        return new BigDecimal[] {new BigDecimal(a), new BigDecimal(b), new BigDecimal(c)};
    }

    private static Backtester.CutoffResult result(
            long seed,
            String cutoff,
            BigDecimal[] actual,
            BigDecimal[] p10,
            BigDecimal[] p50,
            BigDecimal[] p90,
            Backtester.Outcome minimum) {
        return new Backtester.CutoffResult(
                seed,
                Archetype.CONSULTANCY,
                LocalDate.parse(cutoff),
                1000.0,
                actual,
                p10,
                p50,
                p90,
                three("0", "0", "0"),
                three("0", "0", "0"),
                three("0", "0", "0"),
                Map.of("minimum", minimum, "zero", new Backtester.Outcome(false, -1, false, false, false)));
    }

    private static Backtester.Outcome outcome(int firstBreach, boolean runway) {
        return new Backtester.Outcome(true, firstBreach, runway, false, false);
    }

    @Test
    void errorsAndCoverageAreExact() {
        List<Backtester.CutoffResult> rs = List.of(
                // Actual between p50 and p90: inside the band.
                result(
                        1,
                        "2025-01-01",
                        three("100", "100", "100"),
                        three("0", "0", "0"),
                        three("90", "90", "90"),
                        three("100", "100", "100"),
                        outcome(-1, false)),
                // Actual above p90: outside.
                result(
                        1,
                        "2025-01-15",
                        three("200", "200", "200"),
                        three("0", "0", "0"),
                        three("150", "150", "150"),
                        three("190", "190", "190"),
                        outcome(-1, false)));

        BacktestReport.HorizonMetrics h = ReportBuilder.horizons(rs).getFirst();

        assertThat(h.maeRunway()).isEqualByComparingTo("30"); // (10 + 50) / 2
        assertThat(h.maeFlat()).isEqualByComparingTo("150"); // flat = 0: (100 + 200) / 2
        assertThat(h.biasRunway()).isEqualByComparingTo("-30");
        assertThat(h.coverage()).isEqualTo(0.5);
        assertThat(h.relativeMaeRunway()).isCloseTo(0.03, org.assertj.core.data.Offset.offset(1e-12)); // 30 / 1000
    }

    @Test
    void shortfallsCountOnlyBreachesAWeekAwayAndGroupThemIntoEvents() {
        BigDecimal[] z = three("0", "0", "0");
        List<Backtester.CutoffResult> rs = List.of(
                // Breach in 5 days: too late to act, not a real shortfall; the alert is not false either.
                result(1, "2025-01-01", z, z, z, z, outcome(5, true)),
                // One event on 31 Jan seen from two cutoffs: missed at the first, warned at the second.
                result(2, "2025-01-01", z, z, z, z, outcome(30, false)),
                result(2, "2025-01-15", z, z, z, z, outcome(16, true)),
                // An alert with no breach within 90 days: a false alarm.
                result(3, "2025-01-01", z, z, z, z, outcome(-1, true)),
                result(3, "2025-01-15", z, z, z, z, outcome(-1, false)),
                // Already below the threshold: not eligible.
                result(4, "2025-01-01", z, z, z, z, new Backtester.Outcome(false, 1, true, false, false)));

        BacktestReport.ShortfallMetrics m = ReportBuilder.shortfalls(rs).get("minimum");
        BacktestReport.Warnings w = m.methods().get("runway");

        assertThat(m.eligible()).isEqualTo(5);
        assertThat(m.realShortfalls()).isEqualTo(2);
        assertThat(m.events()).isEqualTo(1);
        assertThat(w.warned()).isEqualTo(1);
        assertThat(w.recall()).isEqualTo(0.5);
        assertThat(w.alerts()).isEqualTo(3);
        assertThat(w.falseAlarms()).isEqualTo(1);
        assertThat(w.falseAlarmShare()).isEqualTo(1.0 / 3);
        assertThat(w.eventsWarnedAny()).isEqualTo(1);
        assertThat(w.eventsWarnedFirst()).isZero();
        assertThat(w.medianFirstWarningLeadDays()).isEqualTo(16);
        assertThat(m.methods().get("cushion").alerts()).isZero();
    }

    @Test
    void theActualBalanceIsTheOneOnCutoffPlusHorizon() {
        LocalDate cutoff = Backtester.FIRST_CUTOFF;
        SyntheticCompany c = new SyntheticCompany(
                1,
                "test",
                Archetype.CONSULTANCY,
                LocalDate.of(2023, 12, 31),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                cutoff.plusDays(100),
                List.of(new BankTransaction(cutoff.plusDays(7), new BigDecimal("1000"), "x", "x")),
                List.of());

        Backtester.CutoffResult first = new Backtester()
                .runCompany(c, min -> ForecastSettings.defaults(min).withPaths(20))
                .getFirst();

        assertThat(first.cutoff()).isEqualTo(cutoff);
        assertThat(first.actual()[0]).isEqualByComparingTo("1000");
        assertThat(first.flat()[0]).isEqualByComparingTo("0");
    }

    @Test
    void bootstrapIntervalsContainThePointEstimate() {
        List<Backtester.CutoffResult> rs = new Backtester()
                .run(List.of(1L, 2L, 3L, 4L), min -> ForecastSettings.defaults(min)
                        .withPaths(50));
        BacktestReport.HorizonMetrics h30 = ReportBuilder.horizons(rs).get(1);
        double point = 1 - h30.maeRunway().doubleValue() / h30.maeFlat().doubleValue();

        BacktestReport.Interval ci = ReportBuilder.confidence(rs, 200, 1L).get("mae30ReductionVsFlat");

        assertThat(ci.low()).isLessThanOrEqualTo(point);
        assertThat(ci.high()).isGreaterThanOrEqualTo(point);
    }
}
