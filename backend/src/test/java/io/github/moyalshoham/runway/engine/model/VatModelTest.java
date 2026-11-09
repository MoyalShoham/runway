package io.github.moyalshoham.runway.engine.model;

import static io.github.moyalshoham.runway.engine.Fixtures.bill;
import static io.github.moyalshoham.runway.engine.Fixtures.invoice;
import static io.github.moyalshoham.runway.engine.Fixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VatModelTest {

    private static final VatSettings IL = VatSettings.israel();

    @Test
    void bimonthlyPeriodsArePaidOnTheFifteenthOfTheFollowingMonth() {
        VatModel m =
                VatModel.fit(List.of(), List.of(), LocalDate.parse("2025-01-01"), LocalDate.parse("2025-06-01"), IL);

        VatModel.Period p = m.periodOf(LocalDate.parse("2025-04-20"));
        assertThat(p.start()).isEqualTo(LocalDate.parse("2025-03-01"));
        assertThat(p.end()).isEqualTo(LocalDate.parse("2025-04-30"));
        assertThat(p.nominalPaymentDate()).isEqualTo(LocalDate.parse("2025-05-15"));
        // Jan-Feb is due 15 Mar 2025, a Saturday: the payment moves to Sunday the 16th.
        assertThat(m.periodOf(LocalDate.parse("2025-02-10")).nominalPaymentDate())
                .isEqualTo(LocalDate.parse("2025-03-16"));
    }

    @Test
    void vatIsThePortionOfGrossAtTheRateInForceOnTheDocumentDate() {
        VatModel m =
                VatModel.fit(List.of(), List.of(), LocalDate.parse("2024-01-01"), LocalDate.parse("2025-06-01"), IL);

        // 18% from 1 January 2025, 17% before.
        assertThat(m.signedVat(DocumentType.INVOICE, new BigDecimal("11800.00"), LocalDate.parse("2025-01-01")))
                .isEqualByComparingTo("1800.00");
        assertThat(m.signedVat(DocumentType.INVOICE, new BigDecimal("11700.00"), LocalDate.parse("2024-12-31")))
                .isEqualByComparingTo("1700.00");
        assertThat(m.signedVat(DocumentType.BILL, new BigDecimal("1180.00"), LocalDate.parse("2025-03-01")))
                .isEqualByComparingTo("-180.00");
        assertThat(IL.rateOn(LocalDate.parse("2014-01-01"))).isEqualByComparingTo("0.18");
        assertThat(IL.rateOn(LocalDate.parse("2000-01-01"))).isEqualByComparingTo("0.17");
    }

    @Test
    void withoutVatPaymentsInTheHistoryTheModelIsOff() {
        var docs = List.of(invoice("1", "Acme", "2025-01-10", "2025-02-10", "11800", null));
        VatModel m = VatModel.fit(List.of(), docs, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-06-01"), IL);

        assertThat(m.enabled()).isFalse();
        assertThat(m.periodsPayable(LocalDate.parse("2025-06-01"), LocalDate.parse("2025-09-01")))
                .isEmpty();
    }

    @Test
    void residualCapturesVatThatDocumentsDoNotExplain() {
        // Each 2025 period: invoices with 1,800 VAT and bills with 180 VAT, but 2,000 was paid
        // (card sales carry extra output VAT). Residual = 2,000 - 1,620 = 380.
        List<Document> docs = new ArrayList<>();
        List<BankTransaction> vat = new ArrayList<>();
        String[] periodStarts = {"2025-01-05", "2025-03-05", "2025-05-05", "2025-07-05"};
        String[] payments = {"2025-03-16", "2025-05-15", "2025-07-15", "2025-09-15"};
        for (int i = 0; i < periodStarts.length; i++) {
            docs.add(invoice("I" + i, "Acme", periodStarts[i], periodStarts[i], "11800", periodStarts[i]));
            docs.add(bill("B" + i, "Supplier", periodStarts[i], periodStarts[i], "1180", periodStarts[i]));
            vat.add(tx(payments[i], "-2000", "Israel Tax Authority VAT"));
        }
        VatModel m = VatModel.fit(vat, docs, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-10-10"), IL);

        assertThat(m.enabled()).isTrue();
        assertThat(m.residual()).isEqualByComparingTo("380.00");
        assertThat(m.knownDocumentVat(m.periodOf(LocalDate.parse("2025-07-01"))))
                .isEqualByComparingTo("1620.00");
    }

    @Test
    void aLatePaymentIsStillExpectedButAnOldOneIsNot() {
        List<BankTransaction> vat = List.of(tx("2025-03-16", "-2000", "VAT"), tx("2025-05-15", "-2000", "VAT"));
        VatModel m = VatModel.fit(vat, List.of(), LocalDate.parse("2025-01-01"), LocalDate.parse("2025-07-18"), IL);

        // Due 15 Jul, not paid by 18 Jul: still expected. The September payment is in the horizon too.
        List<VatModel.Period> due = m.periodsPayable(LocalDate.parse("2025-07-18"), LocalDate.parse("2025-10-01"));
        assertThat(due)
                .extracting(VatModel.Period::start)
                .containsExactly(LocalDate.parse("2025-05-01"), LocalDate.parse("2025-07-01"));
        // A month later the July payment is considered missed, not pending.
        assertThat(m.periodsPayable(LocalDate.parse("2025-08-20"), LocalDate.parse("2025-10-01")))
                .extracting(VatModel.Period::start)
                .containsExactly(LocalDate.parse("2025-07-01"));
    }

    @Test
    void defaultPatternRecognisesHebrewAndEnglishVatPayees() {
        var pattern = VatSettings.DEFAULT_PATTERN;
        assertThat(pattern.matcher(io.github.moyalshoham.runway.engine.domain.Counterparties.key("מע\"מ - תשלום"))
                        .find())
                .isTrue();
        assertThat(pattern.matcher(io.github.moyalshoham.runway.engine.domain.Counterparties.key(
                                "Israel Tax Authority VAT"))
                        .find())
                .isTrue();
        assertThat(pattern.matcher(io.github.moyalshoham.runway.engine.domain.Counterparties.key("Private Clinic"))
                        .find())
                .isFalse();
    }
}
