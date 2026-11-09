package io.github.moyalshoham.runway.engine.model;

import static io.github.moyalshoham.runway.engine.Fixtures.invoice;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DelayModelTest {

    private static final LocalDate AS_OF = LocalDate.parse("2025-12-31");

    /** Invoices due on the 1st of each month in 2025, paid {@code delay} days late. */
    private static List<Document> history(String customer, int delay, int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate due = LocalDate.parse("2025-01-01").plusMonths(i);
            docs.add(invoice(
                    customer + "-" + i,
                    customer,
                    due.minusDays(30).toString(),
                    due.toString(),
                    "1000",
                    due.plusDays(delay).toString()));
        }
        return docs;
    }

    private static double meanSample(DelayModel m, String key, int overdue) {
        SplittableRandom rng = new SplittableRandom(1);
        return IntStream.range(0, 2000)
                .map(i -> m.sample(key, overdue, rng))
                .average()
                .orElseThrow();
    }

    @Test
    void withoutHistoryPaymentIsAssumedOnTheDueDate() {
        DelayModel m = DelayModel.fit(List.of(), DocumentType.INVOICE, AS_OF, 3);

        assertThat(m.sample("acme", -10, new SplittableRandom(1))).isZero();
        assertThat(m.sample("acme", 5, new SplittableRandom(1))).isEqualTo(6);
    }

    @Test
    void aCustomerWithALongRecordBehavesLikeItself() {
        List<Document> docs = new ArrayList<>(history("slow", 40, 11));
        docs.addAll(history("fast", 0, 11));
        DelayModel m = DelayModel.fit(docs, DocumentType.INVOICE, AS_OF, 3);

        // 11 / (11 + 3) of draws come from the customer's own 40-day delays, the rest from the
        // pooled delays (half 40, half 0): 0.786 * 40 + 0.214 * 20 = 35.7.
        assertThat(meanSample(m, "slow", -30)).isBetween(34.5, 37.0);
        assertThat(m.meanDelay("slow")).isBetween(35.6, 35.8);
    }

    @Test
    void aNewCustomerBehavesLikeEveryone() {
        List<Document> docs = new ArrayList<>(history("slow", 40, 11));
        docs.addAll(history("fast", 0, 11));
        DelayModel m = DelayModel.fit(docs, DocumentType.INVOICE, AS_OF, 3);

        assertThat(m.observations("newco")).isZero();
        assertThat(meanSample(m, "newco", -30)).isBetween(17.0, 23.0);
    }

    @Test
    void anInvoiceAlreadyOverdueIsPaidAfterToday() {
        DelayModel m = DelayModel.fit(history("acme", 10, 11), DocumentType.INVOICE, AS_OF, 3);

        SplittableRandom rng = new SplittableRandom(3);
        for (int i = 0; i < 500; i++) {
            // 45 days overdue is longer than any delay on record: the tail takes over.
            assertThat(m.sample("acme", 45, rng)).isGreaterThan(45);
        }
    }

    @Test
    void paymentsAfterAsOfAreNotUsed() {
        List<Document> docs = history("acme", 60, 11);
        DelayModel m = DelayModel.fit(docs, DocumentType.INVOICE, LocalDate.parse("2025-03-15"), 3);

        // Only the invoice due 1 Jan (paid 2 Mar) was paid by 15 Mar.
        assertThat(m.observations("acme")).isEqualTo(1);
    }

    @Test
    void billsAndInvoicesAreSeparate() {
        DelayModel m = DelayModel.fit(history("acme", 10, 5), DocumentType.BILL, AS_OF, 3);

        assertThat(m.observations("acme")).isZero();
    }
}
