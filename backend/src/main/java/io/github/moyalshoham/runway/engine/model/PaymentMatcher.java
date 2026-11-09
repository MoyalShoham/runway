package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Links paid invoices and bills to the bank transactions that settled them, so those transactions
 * are modelled once (by the document model) and not again as recurring or irregular flows.
 *
 * <p>A transaction settles a document when it has the same signed amount and is booked within
 * {@link #DATE_TOLERANCE_DAYS} of the recorded payment date. Among candidates, a matching
 * counterparty wins, then the closest date. Partial and combined payments are not matched; the
 * README lists this as a limitation (full reconciliation is what the separate tally project does).
 */
public final class PaymentMatcher {

    public static final int DATE_TOLERANCE_DAYS = 3;

    private PaymentMatcher() {}

    /** Indexes into {@code transactions} of the transactions that settle one of {@code documents}. */
    public static Set<Integer> settledTransactions(List<BankTransaction> transactions, List<Document> documents) {
        Map<BigDecimal, List<Integer>> byAmount = new HashMap<>();
        for (int i = 0; i < transactions.size(); i++) {
            byAmount.computeIfAbsent(transactions.get(i).amount(), k -> new ArrayList<>())
                    .add(i);
        }
        List<Document> paid = documents.stream()
                .filter(d -> d.paidDate() != null)
                .sorted(Comparator.comparing(Document::paidDate).thenComparing(Document::number))
                .toList();
        Set<Integer> used = new HashSet<>();
        for (Document doc : paid) {
            List<Integer> candidates = byAmount.getOrDefault(doc.signedAmount(), List.of());
            String docParty = Counterparties.key(doc.counterparty());
            Integer best = null;
            long bestScore = Long.MAX_VALUE;
            for (int i : candidates) {
                if (used.contains(i)) {
                    continue;
                }
                BankTransaction t = transactions.get(i);
                long distance = Math.abs(ChronoUnit.DAYS.between(doc.paidDate(), t.date()));
                if (distance > DATE_TOLERANCE_DAYS) {
                    continue;
                }
                boolean sameParty = Counterparties.key(t.party()).equals(docParty);
                long score = distance + (sameParty ? 0 : 100);
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best != null) {
                used.add(best);
            }
        }
        return used;
    }
}
