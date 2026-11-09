package io.github.moyalshoham.runway.sim;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A generated company: its full bank history and documents from {@code openingDate} to {@code end},
 * as they are known on {@code end}. Backtests cut it at earlier dates with
 * {@link io.github.moyalshoham.runway.engine.domain.CompanySnapshot#of}.
 */
public record SyntheticCompany(
        long seed,
        String name,
        Archetype archetype,
        LocalDate openingDate,
        BigDecimal openingBalance,
        BigDecimal minimumBalance,
        LocalDate end,
        List<BankTransaction> transactions,
        List<Document> documents) {

    public SyntheticCompany {
        transactions = List.copyOf(transactions);
        documents = List.copyOf(documents);
    }

    /** Closing balance on {@code date}, from the full (future-including) history. */
    public BigDecimal balanceOn(LocalDate date) {
        BigDecimal b = openingBalance;
        for (BankTransaction t : transactions) {
            if (t.date().isAfter(openingDate) && !t.date().isAfter(date)) {
                b = b.add(t.amount());
            }
        }
        return b;
    }
}
