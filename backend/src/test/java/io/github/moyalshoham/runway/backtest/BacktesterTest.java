package io.github.moyalshoham.runway.backtest;

import static io.github.moyalshoham.runway.engine.Fixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.model.ForecastSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BacktesterTest {

    @Test
    void flatBaselineRepeatsTodaysBalance() {
        assertThat(Baselines.flat(new BigDecimal("100.00"), 3)).containsOnly(new BigDecimal("100.00"));
    }

    @Test
    void lastThirtyDaysRepeatedReplaysTheWindow() {
        var history = List.of(
                tx("2025-01-01", "-999", "outside the window"),
                tx("2025-01-02", "-100", "first day of the window"),
                tx("2025-01-31", "50", "last day of the window"));

        BigDecimal[] path =
                Baselines.lastThirtyDaysRepeated(new BigDecimal("1000.00"), history, LocalDate.parse("2025-01-31"), 31);

        assertThat(path[0]).isEqualByComparingTo("900");
        assertThat(path[29]).isEqualByComparingTo("950");
        assertThat(path[30]).isEqualByComparingTo("850");
    }

    @Test
    void smokeBacktestProducesComparableMetrics() {
        List<Backtester.CutoffResult> results = new Backtester()
                .run(List.of(1L), min -> ForecastSettings.defaults(min).withPaths(50));

        assertThat(results).hasSizeGreaterThan(30);
        var horizons = ReportBuilder.horizons(results);
        assertThat(horizons).extracting(BacktestReport.HorizonMetrics::days).containsExactly(7, 30, 90);
        // Errors grow with the horizon.
        assertThat(horizons.get(2).maeRunway()).isGreaterThan(horizons.get(0).maeRunway());
        assertThat(ReportBuilder.shortfalls(results)).containsKeys("minimum", "zero");
    }
}
