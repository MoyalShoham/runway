package io.github.moyalshoham.runway.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the headline claim on a small slice of the dev split: the engine beats both naive
 * baselines at every horizon and warns about most real shortfalls. Not the reported numbers
 * (those are on the holdout split, see eval/), just a tripwire for regressions.
 */
class BacktestRegressionTest {

    @Test
    void engineBeatsNaiveBaselinesOnTheSmokeSplit() {
        BacktestReport r = BacktestCli.run("smoke", 100, null, null);

        for (BacktestReport.HorizonMetrics h : r.horizons()) {
            assertThat(h.maeRunway()).as("vs flat at %d days", h.days()).isLessThan(h.maeFlat());
            assertThat(h.maeRunway()).as("vs last-30-days at %d days", h.days()).isLessThan(h.maeRepeat());
        }
        BacktestReport.ShortfallMetrics minimum = r.shortfalls().get("minimum");
        BacktestReport.Warnings runway = minimum.methods().get("runway");
        assertThat(minimum.realShortfalls()).isPositive();
        assertThat(runway.recall())
                .isGreaterThan(minimum.methods().get("lastThirtyDays").recall());
        assertThat(runway.recall()).isGreaterThanOrEqualTo(0.7);
        assertThat(runway.falseAlarmShare())
                .isLessThan(minimum.methods().get("cushion").falseAlarmShare());
    }
}
