package io.github.moyalshoham.runway.sim;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Writes a synthetic company in the CSV formats the import endpoints accept. */
public final class CsvExport {

    private CsvExport() {}

    public static String transactions(SyntheticCompany c, LocalDate until) {
        StringBuilder sb = new StringBuilder("date,amount,description,counterparty\n");
        for (BankTransaction t : c.transactions()) {
            if (!t.date().isAfter(until)) {
                sb.append(t.date())
                        .append(',')
                        .append(t.amount().toPlainString())
                        .append(',')
                        .append(quote(t.description()))
                        .append(',')
                        .append(quote(t.counterparty()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /** Documents issued by {@code until}, with payments made after it removed. */
    public static String documents(SyntheticCompany c, DocumentType type, LocalDate until) {
        StringBuilder sb = new StringBuilder("number,counterparty,issue_date,due_date,amount,paid_date\n");
        for (Document d : c.documents()) {
            if (d.type() != type || d.issueDate().isAfter(until)) {
                continue;
            }
            Document known = d.asKnownOn(until);
            sb.append(quote(known.number()))
                    .append(',')
                    .append(quote(known.counterparty()))
                    .append(',')
                    .append(known.issueDate())
                    .append(',')
                    .append(known.dueDate())
                    .append(',')
                    .append(known.amount().toPlainString())
                    .append(',')
                    .append(known.paidDate() == null ? "" : known.paidDate().toString())
                    .append('\n');
        }
        return sb.toString();
    }

    /** Writes transactions.csv, invoices.csv, bills.csv and company.txt for seed {@code args[0]} into {@code args[1]}. */
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        Path dir = Path.of(args.length > 1 ? args[1] : "../sample-data");
        SyntheticCompany c = new CompanyGenerator().generate(seed);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("transactions.csv"), transactions(c, c.end()), StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("invoices.csv"), documents(c, DocumentType.INVOICE, c.end()), StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("bills.csv"), documents(c, DocumentType.BILL, c.end()), StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("company.txt"),
                    "name: " + c.name() + "\nopening date: " + c.openingDate() + "\nopening balance: "
                            + c.openingBalance().toPlainString() + "\nminimum balance: "
                            + c.minimumBalance().toPlainString() + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
