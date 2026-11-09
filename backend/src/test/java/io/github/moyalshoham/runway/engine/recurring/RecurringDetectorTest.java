package io.github.moyalshoham.runway.engine.recurring;

import static io.github.moyalshoham.runway.engine.Fixtures.ils;
import static io.github.moyalshoham.runway.engine.Fixtures.monthly;
import static io.github.moyalshoham.runway.engine.Fixtures.occurrences;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RecurringDetectorTest {

    private final RecurringDetector detector = new RecurringDetector();

    private List<RecurringPattern> detect(List<BankTransaction> txs, String asOf) {
        return detector
                .detect(new RecurringDetector.Series("party", -1, occurrences(txs)), LocalDate.parse(asOf))
                .stream()
                .map(RecurringDetector.Detection::pattern)
                .toList();
    }

    @Test
    void monthlyRentOnTheFirstSurvivesWeekendShifts() {
        List<BankTransaction> rent = monthly(YearMonth.of(2025, 1), 12, 1, "-8000", "Landlord");

        List<RecurringPattern> found = detect(rent, "2025-12-20");

        assertThat(found).hasSize(1);
        RecurringPattern p = found.getFirst();
        assertThat(p.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(p.anchor()).isEqualTo(1);
        assertThat(p.amount()).isEqualByComparingTo("8000");
        assertThat(p.active()).isTrue();
        // 1 Jan 2026 is a Thursday, a business day.
        assertThat(p.nextNominalDate()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(p.regularity()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void rentIncreaseIsReportedAndTheNewAmountIsUsed() {
        List<BankTransaction> rent = new ArrayList<>(monthly(YearMonth.of(2025, 1), 6, 1, "-8000", "Landlord"));
        rent.addAll(monthly(YearMonth.of(2025, 7), 4, 1, "-8240", "Landlord"));

        RecurringPattern p = detect(rent, "2025-10-15").getFirst();

        assertThat(p.amount()).isEqualByComparingTo("8240");
        assertThat(p.amountChange()).isNotNull();
        assertThat(p.amountChange().from()).isEqualByComparingTo("8000");
        assertThat(p.amountChange().to()).isEqualByComparingTo("8240");
    }

    @Test
    void oneSkippedMonthDoesNotHideAMonthlyPattern() {
        List<BankTransaction> sub = new ArrayList<>(monthly(YearMonth.of(2025, 1), 10, 12, "-299", "Monday.com"));
        sub.remove(4);

        RecurringPattern p = detect(sub, "2025-10-20").getFirst();

        assertThat(p.cadence()).isEqualTo(Cadence.MONTHLY);
    }

    @Test
    void weeklySupplierIsWeekly() {
        List<BankTransaction> txs = new ArrayList<>();
        LocalDate d = LocalDate.parse("2025-03-02");
        for (int i = 0; i < 10; i++) {
            txs.add(new BankTransaction(d.plusWeeks(i), new BigDecimal("-1200"), "Tnuva", "Tnuva"));
        }

        RecurringPattern p = detect(txs, "2025-05-06").getFirst();

        assertThat(p.cadence()).isEqualTo(Cadence.WEEKLY);
        assertThat(p.nextNominalDate()).isEqualTo(LocalDate.parse("2025-05-11"));
    }

    @Test
    void bimonthlyUtilityWithVaryingAmountsIsBimonthly() {
        String[] amounts = {"-640", "-780", "-910", "-720", "-650", "-800", "-880"};
        List<BankTransaction> txs = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            YearMonth m = YearMonth.of(2024, 1).plusMonths(2L * i);
            txs.add(new BankTransaction(m.atDay(20), new BigDecimal(amounts[i]), "IEC", "IEC"));
        }

        RecurringPattern p = detect(txs, "2025-02-01").getFirst();

        assertThat(p.cadence()).isEqualTo(Cadence.BIMONTHLY);
    }

    @Test
    void randomPurchasesAreNotRecurring() {
        Random random = new Random(7);
        List<BankTransaction> txs = new ArrayList<>();
        LocalDate d = LocalDate.parse("2025-01-01");
        for (int i = 0; i < 15; i++) {
            d = d.plusDays(3 + random.nextInt(40));
            txs.add(new BankTransaction(
                    d, BigDecimal.valueOf(-(50 + random.nextInt(900))), "Office Depot", "Office Depot"));
        }

        assertThat(detect(txs, d.toString())).isEmpty();
    }

    @Test
    void subscriptionIsFoundAmongOccasionalPurchasesFromTheSameVendor() {
        List<BankTransaction> txs = new ArrayList<>(monthly(YearMonth.of(2025, 1), 9, 5, "-49.90", "Google"));
        txs.add(new BankTransaction(LocalDate.parse("2025-02-17"), new BigDecimal("-2400"), "Google", "Google"));
        txs.add(new BankTransaction(LocalDate.parse("2025-06-03"), new BigDecimal("-1100"), "Google", "Google"));
        txs.add(new BankTransaction(LocalDate.parse("2025-08-21"), new BigDecimal("-3900"), "Google", "Google"));

        List<RecurringPattern> found = detect(txs, "2025-09-20");

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().amount()).isEqualByComparingTo("49.90");
        assertThat(found.getFirst().key()).startsWith("party#");
        assertThat(found.getFirst().counterpartyKey()).isEqualTo("party");
    }

    @Test
    void cancelledSubscriptionIsInactive() {
        List<BankTransaction> txs = monthly(YearMonth.of(2025, 1), 6, 10, "-120", "Zoom");

        RecurringPattern p = detect(txs, "2025-10-01").getFirst();

        assertThat(p.active()).isFalse();
    }

    @Test
    void endOfMonthSalaryShiftedIntoNextMonthKeepsLastDayAnchor() {
        // Paid on the last day of the month; when that is a Friday or Saturday it lands on Sunday.
        List<BankTransaction> txs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            YearMonth m = YearMonth.of(2025, 1).plusMonths(i);
            LocalDate d = io.github.moyalshoham.runway.engine.domain.BusinessCalendar.nextBusinessDay(m.atEndOfMonth());
            txs.add(new BankTransaction(d, new BigDecimal("-14500"), "Dana Levi", "Dana Levi"));
        }

        RecurringPattern p = detect(txs, "2026-01-05").getFirst();

        assertThat(p.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(p.anchor()).isZero();
        assertThat(p.nextNominalDate()).isEqualTo(LocalDate.parse("2026-02-01")); // 31 Jan 2026 is a Saturday
    }

    @Test
    void sameDayPaymentsToOneCounterpartyAreMerged() {
        List<BankTransaction> txs = new ArrayList<>();
        for (BankTransaction t : monthly(YearMonth.of(2025, 1), 8, 9, "-9000", "Payroll")) {
            txs.add(t);
            txs.add(new BankTransaction(t.date(), new BigDecimal("-11000"), "Payroll", "Payroll"));
        }

        RecurringPattern p = detect(txs, "2025-08-20").getFirst();

        assertThat(p.amount()).isEqualByComparingTo(ils("20000"));
        assertThat(p.occurrences()).isEqualTo(8);
    }

    @Test
    void yearlyInsuranceNeedsOnlyTwoOccurrences() {
        List<BankTransaction> txs = List.of(
                new BankTransaction(LocalDate.parse("2024-03-10"), new BigDecimal("-5200"), "Harel", "Harel"),
                new BankTransaction(LocalDate.parse("2025-03-10"), new BigDecimal("-5400"), "Harel", "Harel"));

        RecurringPattern p = detect(txs, "2025-06-01").getFirst();

        assertThat(p.cadence()).isEqualTo(Cadence.YEARLY);
        assertThat(p.confidence()).isLessThan(0.5);
    }

    @Test
    void twoMonthlyOccurrencesAreNotEnough() {
        assertThat(detect(monthly(YearMonth.of(2025, 1), 2, 3, "-100", "X"), "2025-02-10"))
                .isEmpty();
    }

    @Test
    void nominalMonthHandlesPaymentsThatSlipIntoTheNextMonth() {
        // Anchor 0 (last day): a payment on 2 Feb belongs to January.
        assertThat(RecurringPattern.nominalMonth(LocalDate.parse("2026-02-01"), 0))
                .isEqualTo(YearMonth.of(2026, 1));
        assertThat(RecurringPattern.anchorOf(LocalDate.parse("2026-01-31"))).isZero();
        assertThat(RecurringPattern.anchorOf(LocalDate.parse("2026-01-30"))).isEqualTo(-1);
        assertThat(RecurringPattern.anchorOf(LocalDate.parse("2026-01-09"))).isEqualTo(9);
    }
}
