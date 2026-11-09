package io.github.moyalshoham.runway.engine.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Everything the forecaster is allowed to know on {@code asOf}. Built through {@link #of}, which
 * removes anything dated after {@code asOf} so that a backtest cannot see the future.
 *
 * @param historyStart first day covered by the bank data
 * @param balance closing balance on {@code asOf}
 */
public record CompanySnapshot(
        LocalDate historyStart,
        LocalDate asOf,
        BigDecimal balance,
        List<BankTransaction> transactions,
        List<Document> documents) {

    public CompanySnapshot {
        Objects.requireNonNull(historyStart, "historyStart");
        Objects.requireNonNull(asOf, "asOf");
        balance = Money.round(balance);
        transactions = List.copyOf(transactions);
        documents = List.copyOf(documents);
    }

    /**
     * @param openingBalance balance at the end of {@code openingDate}
     * @param allTransactions transactions after {@code openingDate}, possibly extending past asOf
     * @param allDocuments documents, possibly issued or paid after asOf
     */
    public static CompanySnapshot of(
            LocalDate asOf,
            LocalDate openingDate,
            BigDecimal openingBalance,
            List<BankTransaction> allTransactions,
            List<Document> allDocuments) {
        if (asOf.isBefore(openingDate)) {
            throw new IllegalArgumentException("asOf " + asOf + " is before the opening date " + openingDate);
        }
        List<BankTransaction> known = allTransactions.stream()
                .filter(t -> t.date().isAfter(openingDate) && !t.date().isAfter(asOf))
                .sorted(Comparator.comparing(BankTransaction::date))
                .toList();
        BigDecimal balance = openingBalance;
        for (BankTransaction t : known) {
            balance = balance.add(t.amount());
        }
        List<Document> docs = allDocuments.stream()
                .filter(d -> !d.issueDate().isAfter(asOf))
                .map(d -> d.asKnownOn(asOf))
                .toList();
        return new CompanySnapshot(openingDate.plusDays(1), asOf, balance, known, docs);
    }
}
