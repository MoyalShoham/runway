package io.github.moyalshoham.runway.engine.model;

import static io.github.moyalshoham.runway.engine.Fixtures.bill;
import static io.github.moyalshoham.runway.engine.Fixtures.invoice;
import static io.github.moyalshoham.runway.engine.Fixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentMatcherTest {

    @Test
    void matchesSameAmountWithinTheDateTolerance() {
        var txs = List.of(tx("2025-05-12", "11800", "Acme"), tx("2025-05-12", "-2360", "Bezeq"));
        var docs = List.of(
                invoice("1001", "Acme", "2025-04-01", "2025-05-01", "11800", "2025-05-10"),
                bill("B-7", "Bezeq", "2025-04-20", "2025-05-12", "2360", "2025-05-12"));

        assertThat(PaymentMatcher.settledTransactions(txs, docs)).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void doesNotMatchOutsideTheTolerance() {
        var txs = List.of(tx("2025-05-20", "11800", "Acme"));
        var docs = List.of(invoice("1001", "Acme", "2025-04-01", "2025-05-01", "11800", "2025-05-10"));

        assertThat(PaymentMatcher.settledTransactions(txs, docs)).isEmpty();
    }

    @Test
    void signMatters() {
        var txs = List.of(tx("2025-05-10", "-11800", "Acme"));
        var docs = List.of(invoice("1001", "Acme", "2025-04-01", "2025-05-01", "11800", "2025-05-10"));

        assertThat(PaymentMatcher.settledTransactions(txs, docs)).isEmpty();
    }

    @Test
    void oneTransactionSettlesAtMostOneDocumentAndTheCounterpartyBreaksTies() {
        var txs = List.of(tx("2025-05-10", "5900", "Other Ltd"), tx("2025-05-11", "5900", "Acme Ltd."));
        var docs = List.of(
                invoice("1", "Acme", "2025-04-01", "2025-05-01", "5900", "2025-05-10"),
                invoice("2", "Other", "2025-04-01", "2025-05-01", "5900", "2025-05-10"));

        // Invoice 1 (Acme) takes the Acme transaction despite it being a day further away.
        assertThat(PaymentMatcher.settledTransactions(txs, docs)).containsExactlyInAnyOrder(0, 1);
        var onlyOne = List.of(tx("2025-05-10", "5900", "Acme"));
        assertThat(PaymentMatcher.settledTransactions(onlyOne, docs)).containsExactly(0);
    }

    @Test
    void openDocumentsSettleNothing() {
        var txs = List.of(tx("2025-05-10", "5900", "Acme"));
        var docs = List.of(invoice("1", "Acme", "2025-04-01", "2025-05-01", "5900", null));

        assertThat(PaymentMatcher.settledTransactions(txs, docs)).isEmpty();
    }

    @Test
    void aPaymentNetOfWithholdingTaxFromTheSameCustomerMatches() {
        // Invoice 11,800 (10,000 + VAT); the customer withholds 5% of the net at source and pays 11,300.
        var txs = List.of(tx("2025-05-11", "11300", "Acme Ltd"), tx("2025-05-11", "11300", "Someone Else"));
        var docs = List.of(invoice("1001", "Acme", "2025-04-01", "2025-05-01", "11800", "2025-05-10"));

        assertThat(PaymentMatcher.settledTransactions(txs, docs)).containsExactly(0);
    }

    @Test
    void tooSmallAPartAndOtherCustomersDoNotMatch() {
        var docs = List.of(invoice("1001", "Acme", "2025-04-01", "2025-05-01", "11800", "2025-05-10"));

        assertThat(PaymentMatcher.settledTransactions(List.of(tx("2025-05-10", "5900", "Acme")), docs))
                .isEmpty();
        assertThat(PaymentMatcher.settledTransactions(List.of(tx("2025-05-10", "11300", "Beta")), docs))
                .isEmpty();
    }

    @Test
    void anExactSameCustomerPaymentBeatsAPartialOne() {
        var txs = List.of(tx("2025-05-10", "11300", "Acme"), tx("2025-05-12", "11800", "Acme"));
        var docs = List.of(invoice("1001", "Acme", "2025-04-01", "2025-05-01", "11800", "2025-05-10"));

        assertThat(PaymentMatcher.settledTransactions(txs, docs)).containsExactly(1);
    }
}
