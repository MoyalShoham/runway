package io.github.moyalshoham.runway.engine.domain;

import static io.github.moyalshoham.runway.engine.Fixtures.invoice;
import static io.github.moyalshoham.runway.engine.Fixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompanySnapshotTest {

    @Test
    void hidesEverythingAfterAsOf() {
        var txs = List.of(
                tx("2025-01-01", "-100", "Before opening"),
                tx("2025-01-05", "500", "A"),
                tx("2025-01-10", "-200", "B"),
                tx("2025-01-11", "-999", "Future"));
        var docs = List.of(
                invoice("1", "Acme", "2025-01-02", "2025-02-01", "1000", "2025-01-20"),
                invoice("2", "Acme", "2025-01-12", "2025-02-12", "1000", null));

        CompanySnapshot s = CompanySnapshot.of(
                LocalDate.parse("2025-01-10"), LocalDate.parse("2025-01-01"), new BigDecimal("1000"), txs, docs);

        assertThat(s.balance()).isEqualByComparingTo("1300");
        assertThat(s.transactions()).hasSize(2);
        assertThat(s.historyStart()).isEqualTo(LocalDate.parse("2025-01-02"));
        // Invoice 1 was paid after asOf, so it is still open; invoice 2 was not issued yet.
        assertThat(s.documents()).singleElement().satisfies(d -> {
            assertThat(d.number()).isEqualTo("1");
            assertThat(d.paidDate()).isNull();
        });
    }

    @Test
    void rejectsAsOfBeforeOpening() {
        assertThatThrownBy(() -> CompanySnapshot.of(
                        LocalDate.parse("2024-12-31"),
                        LocalDate.parse("2025-01-01"),
                        BigDecimal.ZERO,
                        List.of(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterpartyKeysIgnoreCaseReferencesAndLegalSuffixes() {
        assertThat(Counterparties.key("BEZEQ Ltd. #88123")).isEqualTo(Counterparties.key("bezeq"));
        assertThat(Counterparties.key("חברת החשמל בע\"מ")).isEqualTo("חברת החשמל");
        assertThat(Counterparties.key("  Wolt   Israel ")).isEqualTo("wolt israel");
    }

    @Test
    void documentsValidateTheirInvariants() {
        assertThatThrownBy(() -> invoice("1", "A", "2025-01-10", "2025-01-01", "100", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoice("1", "A", "2025-01-10", "2025-01-20", "0", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyRoundsHalfEvenToAgorot() {
        assertThat(Money.of("10.005")).isEqualByComparingTo("10.00");
        assertThat(Money.of("10.015")).isEqualByComparingTo("10.02");
        assertThat(Money.times(new BigDecimal("100.00"), 1.035)).isEqualByComparingTo("103.50");
        assertThat(Money.vatPortion(new BigDecimal("118.00"), new BigDecimal("0.18")))
                .isEqualByComparingTo("18.00");
    }

    @Test
    void israeliWeekendMovesToSunday() {
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.parse("2025-11-07")))
                .isEqualTo(LocalDate.parse("2025-11-09"));
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.parse("2025-11-08")))
                .isEqualTo(LocalDate.parse("2025-11-09"));
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.parse("2025-11-06")))
                .isEqualTo(LocalDate.parse("2025-11-06"));
    }
}
