package io.github.moyalshoham.runway.importer;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Parses bank-transaction and document CSV files. Untrusted input: the file is decoded strictly
 * (UTF-8, or Windows-1255 as exported by Israeli banks), binary content is refused, the number of rows
 * and the length of every field are bounded, and each value is validated rather than coerced.
 *
 * <p>Headers are matched case-insensitively with English and Hebrew aliases, and the delimiter
 * (comma, semicolon or tab) is detected from the header line.
 */
public final class CsvParser {

    public static final int MAX_ROWS = 50_000;
    static final int MAX_ERRORS = 200;
    private static final Charset WINDOWS_1255 = Charset.forName("windows-1255");

    private static final Map<String, List<String>> TX_COLUMNS = Map.of(
            // Listed by priority: when a file has both a booking date and a value date, the booking
            // date wins (it is the date the bank statement and balance use).
            "date", List.of("date", "booking_date", "booking date", "תאריך", "value_date", "תאריך ערך"),
            "amount", List.of("amount", "סכום"),
            "debit", List.of("debit", "withdrawal", "חובה"),
            "credit", List.of("credit", "deposit", "זכות"),
            "description", List.of("description", "details", "memo", "תיאור", "תאור", "פרטים", "תיאור התנועה"),
            "counterparty", List.of("counterparty", "payee", "name", "מוטב", "שם"));

    private static final Map<String, List<String>> DOC_COLUMNS = Map.of(
            "number", List.of("number", "invoice_number", "bill_number", "document_number", "מספר", "מספר מסמך"),
            "counterparty", List.of("counterparty", "customer", "vendor", "supplier", "לקוח", "ספק"),
            "issue_date", List.of("issue_date", "issued", "date", "תאריך", "תאריך הפקה"),
            "due_date", List.of("due_date", "due", "תאריך פירעון", "לתשלום עד"),
            "amount", List.of("amount", "total", "סכום", "סה\"כ"),
            "paid_date", List.of("paid_date", "paid", "תאריך תשלום"));

    private CsvParser() {}

    public static Parsed<BankTransaction> transactions(byte[] content) {
        Table t = table(content, TX_COLUMNS);
        boolean single = t.has("amount");
        if (!t.has("date") || !(single || (t.has("debit") && t.has("credit")))) {
            throw new CsvRejectedException(
                    "Transaction files need a date column and either an amount column or debit and credit columns");
        }
        if (!t.has("description") && !t.has("counterparty")) {
            throw new CsvRejectedException("Transaction files need a description or counterparty column");
        }
        List<Parsed.Line<BankTransaction>> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int k = 0; k < t.records().size(); k++) {
            CSVRecord r = t.records().get(k);
            long line = t.lines().get(k);
            try {
                LocalDate date = Values.date(t.get(r, "date"), "date");
                BigDecimal amount;
                if (single) {
                    amount = Values.amount(t.get(r, "amount"), "amount");
                } else {
                    String debit = Values.blankOrZero(t.get(r, "debit"))
                            ? ""
                            : t.get(r, "debit").strip();
                    String credit = Values.blankOrZero(t.get(r, "credit"))
                            ? ""
                            : t.get(r, "credit").strip();
                    if (debit.isEmpty() == credit.isEmpty()) {
                        throw new IllegalArgumentException("exactly one of debit and credit must be non-zero");
                    }
                    amount = debit.isEmpty()
                            ? Values.amount(credit, "credit").abs()
                            : Values.amount(debit, "debit").abs().negate();
                }
                if (amount.signum() == 0) {
                    throw new IllegalArgumentException("amount is zero");
                }
                String description = Values.text(t.get(r, "description"), "description", 500, false);
                String counterparty = Values.text(t.get(r, "counterparty"), "counterparty", 200, false);
                if (description.isEmpty() && counterparty.isEmpty()) {
                    throw new IllegalArgumentException("description and counterparty are both empty");
                }
                rows.add(new Parsed.Line<>(line, new BankTransaction(date, amount, description, counterparty)));
            } catch (IllegalArgumentException e) {
                addError(errors, line, e.getMessage());
            }
        }
        return new Parsed<>(rows, errors);
    }

    public static Parsed<Document> documents(byte[] content, DocumentType type) {
        Table t = table(content, DOC_COLUMNS);
        for (String required : List.of("number", "counterparty", "issue_date", "due_date", "amount")) {
            if (!t.has(required)) {
                throw new CsvRejectedException("Missing column: " + required);
            }
        }
        List<Parsed.Line<Document>> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Set<String> numbers = new HashSet<>();
        for (int k = 0; k < t.records().size(); k++) {
            CSVRecord r = t.records().get(k);
            long line = t.lines().get(k);
            try {
                String number = Values.text(t.get(r, "number"), "number", 100, true);
                if (!numbers.add(number)) {
                    throw new IllegalArgumentException("duplicate document number in file: " + Values.truncate(number));
                }
                String party = Values.text(t.get(r, "counterparty"), "counterparty", 200, true);
                LocalDate issued = Values.date(t.get(r, "issue_date"), "issue_date");
                LocalDate due = Values.date(t.get(r, "due_date"), "due_date");
                if (due.isBefore(issued)) {
                    throw new IllegalArgumentException("due_date is before issue_date");
                }
                BigDecimal amount = Values.amount(t.get(r, "amount"), "amount");
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException("amount must be positive");
                }
                String paidRaw = t.get(r, "paid_date").strip();
                LocalDate paid = paidRaw.isEmpty() ? null : Values.date(paidRaw, "paid_date");
                if (paid != null && paid.isBefore(issued)) {
                    throw new IllegalArgumentException(
                            "paid_date is before issue_date (prepayments are not supported)");
                }
                rows.add(new Parsed.Line<>(line, new Document(type, number, party, issued, due, amount, paid)));
            } catch (IllegalArgumentException e) {
                addError(errors, line, e.getMessage());
            }
        }
        return new Parsed<>(rows, errors);
    }

    private static void addError(List<RowError> errors, long line, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new RowError(line, message));
        }
    }

    // ---- decoding and table access ------------------------------------------------------------

    /** Records with the physical line (1-based, header = 1) on which each one ends. */
    private record Table(Map<String, Integer> columns, List<CSVRecord> records, List<Long> lines) {
        boolean has(String column) {
            return columns.containsKey(column);
        }

        String get(CSVRecord r, String column) {
            Integer i = columns.get(column);
            return i == null || i >= r.size() ? "" : r.get(i);
        }
    }

    private static Table table(byte[] content, Map<String, List<String>> aliases) {
        String text = decode(content);
        String headerLine = text.lines().findFirst().orElse("");
        if (headerLine.isBlank()) {
            throw new CsvRejectedException("The file is empty");
        }
        char delimiter = detectDelimiter(headerLine);
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<CSVRecord> all = new ArrayList<>();
            List<Long> lines = new ArrayList<>();
            for (CSVRecord r : parser) {
                all.add(r);
                lines.add(parser.getCurrentLineNumber());
                if (all.size() > MAX_ROWS + 1) {
                    throw new CsvRejectedException("More than " + MAX_ROWS + " rows; split the file");
                }
            }
            if (all.isEmpty()) {
                throw new CsvRejectedException("The file is empty");
            }
            Map<String, Integer> columns = mapHeader(all.getFirst(), aliases);
            return new Table(columns, all.subList(1, all.size()), lines.subList(1, lines.size()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UncheckedIOException e) {
            // commons-csv reports bad quoting this way while iterating, with the line number.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new CsvRejectedException("Malformed CSV: " + Values.truncate200(String.valueOf(cause.getMessage())));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new CsvRejectedException("Malformed CSV: " + Values.truncate200(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Maps header names to logical columns. When several headers map to the same column (a bank
     * export with both a booking date and a value date), the alias listed first wins.
     */
    private static Map<String, Integer> mapHeader(CSVRecord header, Map<String, List<String>> aliases) {
        Map<String, Integer> columns = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip().toLowerCase(Locale.ROOT).replace('״', '"');
            for (Map.Entry<String, List<String>> e : aliases.entrySet()) {
                int priority = e.getValue().indexOf(name);
                if (priority < 0) {
                    continue;
                }
                Integer current = rank.get(e.getKey());
                if (current == null || priority < current) {
                    columns.put(e.getKey(), i);
                    rank.put(e.getKey(), priority);
                } else if (priority == current) {
                    throw new CsvRejectedException("Two columns are both called " + name);
                }
            }
        }
        return columns;
    }

    static char detectDelimiter(String headerLine) {
        long commas = headerLine.chars().filter(c -> c == ',').count();
        long semicolons = headerLine.chars().filter(c -> c == ';').count();
        long tabs = headerLine.chars().filter(c -> c == '\t').count();
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    /**
     * UTF-16 when it starts with a byte-order mark (Excel's "Unicode text"); otherwise strict UTF-8
     * (BOM stripped), falling back to Windows-1255. NUL bytes in an 8-bit file mean binary, refused.
     */
    static String decode(byte[] content) {
        if (content.length >= 2
                && ((content[0] == (byte) 0xFF && content[1] == (byte) 0xFE)
                        || (content[0] == (byte) 0xFE && content[1] == (byte) 0xFF))) {
            try {
                String text = StandardCharsets.UTF_16
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content))
                        .toString();
                if (text.indexOf('\0') >= 0) {
                    throw new CsvRejectedException("This looks like a binary file, not CSV");
                }
                return text.startsWith("\uFEFF") ? text.substring(1) : text;
            } catch (CharacterCodingException e) {
                throw new CsvRejectedException("The file is not valid UTF-16 text");
            }
        }
        for (byte b : content) {
            if (b == 0) {
                throw new CsvRejectedException("This looks like a binary file, not CSV");
            }
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            try {
                text = WINDOWS_1255
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content))
                        .toString();
            } catch (CharacterCodingException neither) {
                throw new CsvRejectedException("The file is neither UTF-8 nor Windows-1255 text");
            }
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
