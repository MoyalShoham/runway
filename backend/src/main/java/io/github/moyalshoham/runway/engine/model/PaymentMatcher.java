package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
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
 * <p>A transaction settles a document when it is booked within {@link #DATE_TOLERANCE_DAYS} of the
 * recorded payment date, has the same sign, and either has exactly the document's amount, or comes
 * from the same counterparty for between {@link #MIN_NET_SHARE} and 100% of it. The second case is
 * Israeli withholding tax at source (ניכוי במקור): large customers pay the invoice minus 2-30% that
 * they remit to the tax authority. Among candidates, an exact amount from the same counterparty wins,
 * then a withheld amount from the same counterparty, then an exact amount from anyone; ties go to the
 * closest date. Payments split over several transfers, or one transfer settling several documents,
 * are not matched (see the README's limitations).
 */
public final class PaymentMatcher {

    public static final int DATE_TOLERANCE_DAYS = 3;
    /** Lowest share of a document that still counts as its payment (30% withheld at source). */
    public static final BigDecimal MIN_NET_SHARE = new BigDecimal("0.70");

    private PaymentMatcher() {}

    /** Indexes into {@code transactions} of the transactions that settle one of {@code documents}. */
    public static Set<Integer> settledTransactions(List<BankTransaction> transactions, List<Document> documents) {
        Map<LocalDate, List<Integer>> byDate = new HashMap<>();
        List<String> keys = new ArrayList<>(transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            byDate.computeIfAbsent(transactions.get(i).date(), k -> new ArrayList<>())
                    .add(i);
            keys.add(Counterparties.key(transactions.get(i).party()));
        }
        List<Document> paid = documents.stream()
                .filter(d -> d.paidDate() != null)
                .sorted(Comparator.comparing(Document::paidDate).thenComparing(Document::number))
                .toList();
        Set<Integer> used = new HashSet<>();
        for (Document doc : paid) {
            String docParty = Counterparties.key(doc.counterparty());
            BigDecimal target = doc.signedAmount();
            Integer best = null;
            long bestScore = Long.MAX_VALUE;
            for (int offset = -DATE_TOLERANCE_DAYS; offset <= DATE_TOLERANCE_DAYS; offset++) {
                for (int i : byDate.getOrDefault(doc.paidDate().plusDays(offset), List.of())) {
                    if (used.contains(i)) {
                        continue;
                    }
                    BankTransaction t = transactions.get(i);
                    if (t.amount().signum() != target.signum()) {
                        continue;
                    }
                    boolean sameParty = keys.get(i).equals(docParty);
                    boolean exact = t.amount().compareTo(target) == 0;
                    long tier;
                    if (exact && sameParty) {
                        tier = 0;
                    } else if (sameParty && withheldShare(t.amount(), target)) {
                        tier = 1;
                    } else if (exact) {
                        tier = 2;
                    } else {
                        continue;
                    }
                    long score = tier * 100 + Math.abs(offset);
                    if (score < bestScore) {
                        bestScore = score;
                        best = i;
                    }
                }
            }
            if (best != null) {
                used.add(best);
            }
        }
        return used;
    }

    /** True when {@code paid} is at least {@link #MIN_NET_SHARE} and less than all of {@code due}. */
    static boolean withheldShare(BigDecimal paid, BigDecimal due) {
        BigDecimal share = paid.divide(due, MathContext.DECIMAL64);
        return share.compareTo(MIN_NET_SHARE) >= 0 && share.compareTo(BigDecimal.ONE) < 0;
    }
}
