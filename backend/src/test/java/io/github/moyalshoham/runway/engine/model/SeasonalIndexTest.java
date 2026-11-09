package io.github.moyalshoham.runway.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SeasonalIndexTest {

    @Test
    void lessThanAYearOfDataGivesAFlatIndex() {
        Map<YearMonth, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < 11; i++) {
            m.put(YearMonth.of(2025, 1).plusMonths(i), BigDecimal.valueOf(i == 7 ? 10 : 100));
        }

        SeasonalIndex idx = SeasonalIndex.fit(m, YearMonth.of(2025, 11));

        assertThat(idx.factor(Month.AUGUST)).isEqualTo(1.0);
    }

    @Test
    void aRecurringAugustDipIsFoundAndShrunk() {
        Map<YearMonth, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < 24; i++) {
            YearMonth ym = YearMonth.of(2024, 1).plusMonths(i);
            m.put(ym, BigDecimal.valueOf(ym.getMonth() == Month.AUGUST ? 50 : 100));
        }

        SeasonalIndex idx = SeasonalIndex.fit(m, YearMonth.of(2025, 12));

        // Raw August ratio is about 0.52; two years of evidence shrink it by 2/3 toward 1.
        assertThat(idx.factor(Month.AUGUST)).isBetween(0.6, 0.75);
        assertThat(idx.factor(Month.MARCH)).isBetween(1.0, 1.06);
    }

    @Test
    void steadyGrowthIsNotSeasonality() {
        Map<YearMonth, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < 36; i++) {
            m.put(YearMonth.of(2023, 1).plusMonths(i), BigDecimal.valueOf(100 + 3L * i));
        }

        SeasonalIndex idx = SeasonalIndex.fit(m, YearMonth.of(2025, 12));

        for (Month month : Month.values()) {
            assertThat(idx.factor(month)).isBetween(0.93, 1.07);
        }
    }

    @Test
    void monthsAfterTheLastCompleteMonthAreIgnored() {
        Map<YearMonth, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < 13; i++) {
            m.put(YearMonth.of(2025, 1).plusMonths(i), BigDecimal.valueOf(100));
        }
        m.put(YearMonth.of(2026, 2), BigDecimal.valueOf(1)); // partial month

        SeasonalIndex idx = SeasonalIndex.fit(m, YearMonth.of(2026, 1));

        assertThat(idx.factor(Month.FEBRUARY)).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
