package io.github.moyalshoham.runway.engine;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.BusinessCalendar;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.engine.recurring.Occurrence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class Fixtures {

    private Fixtures() {}

    public static BigDecimal ils(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    public static BankTransaction tx(String date, String amount, String party) {
        return new BankTransaction(LocalDate.parse(date), new BigDecimal(amount), party, party);
    }

    public static Document invoice(
            String number, String customer, String issued, String due, String amount, String paid) {
        return new Document(
                DocumentType.INVOICE,
                number,
                customer,
                LocalDate.parse(issued),
                LocalDate.parse(due),
                new BigDecimal(amount),
                paid == null ? null : LocalDate.parse(paid));
    }

    public static Document bill(String number, String vendor, String issued, String due, String amount, String paid) {
        return new Document(
                DocumentType.BILL,
                number,
                vendor,
                LocalDate.parse(issued),
                LocalDate.parse(due),
                new BigDecimal(amount),
                paid == null ? null : LocalDate.parse(paid));
    }

    /** One transaction per month on {@code day} (moved to the next business day), {@code months} times. */
    public static List<BankTransaction> monthly(YearMonth first, int months, int day, String amount, String party) {
        List<BankTransaction> txs = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            YearMonth m = first.plusMonths(i);
            LocalDate d = BusinessCalendar.nextBusinessDay(m.atDay(Math.min(day, m.lengthOfMonth())));
            txs.add(new BankTransaction(d, new BigDecimal(amount), party, party));
        }
        return txs;
    }

    public static List<Occurrence> occurrences(List<BankTransaction> txs) {
        return txs.stream()
                .map(t -> new Occurrence(t.date(), t.amount(), t.party()))
                .toList();
    }
}
