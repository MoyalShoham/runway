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
        assertThat(m.typicalDelay("slow")).isEqualTo(40);
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
    void anInvoiceOverdueLongerThanAnyPaymentOnRecordIsNotExpected() {
        DelayModel m = DelayModel.fit(history("acme", 10, 11), DocumentType.INVOICE, AS_OF, 3);

        SplittableRandom rng = new SplittableRandom(3);
        assertThat(m.sample("acme", 45, rng)).isEqualTo(DelayModel.NOT_PAID);
        // 5 days overdue: still within the history, so paid after today.
        for (int i = 0; i < 200; i++) {
            assertThat(m.sample("acme", 5, rng)).isBetween(6, 12);
        }
    }

    @Test
    void openInvoicesAreCensoredObservationsNotIgnored() {
        // Five invoices paid 10 days late, five still open 50 days after their due date.
        List<Document> docs = new ArrayList<>(history("acme", 10, 5));
        for (int i = 0; i < 5; i++) {
            LocalDate due = AS_OF.minusDays(50);
            docs.add(invoice("open-" + i, "acme", due.minusDays(30).toString(), due.toString(), "1000", null));
        }
        DelayModel m = DelayModel.fit(docs, DocumentType.INVOICE, AS_OF, 3);

        // Learning from paid invoices only would say "always 10 days late". Kaplan-Meier keeps the
        // open half: about half of fresh invoices are not paid within any delay seen so far.
        SplittableRandom rng = new SplittableRandom(5);
        long notPaid = IntStream.range(0, 4000)
                .filter(i -> m.sample("acme", -30, rng) == DelayModel.NOT_PAID)
                .count();
        assertThat(notPaid / 4000.0).isBetween(0.45, 0.55);
    }

    @Test
    void paymentsAfterAsOfAreNotSeen() {
        List<Document> docs = history("acme", 60, 11);
        DelayModel m = DelayModel.fit(docs, DocumentType.INVOICE, LocalDate.parse("2025-03-15"), 3);

        // Issued by 15 Mar: invoices due 1 Jan..1 Apr. Only the first (paid 2 Mar) is a payment; the
        // other three are open on 15 Mar, so they count as censored, not as paid after 60 days.
        assertThat(m.observations("acme")).isEqualTo(4);
        assertThat(m.sample("acme", -30, new SplittableRandom(1))).isBetween(58, 62);
    }

    @Test
    void billsAndInvoicesAreSeparate() {
        DelayModel m = DelayModel.fit(history("acme", 10, 5), DocumentType.BILL, AS_OF, 3);

        assertThat(m.observations("acme")).isZero();
    }
}
