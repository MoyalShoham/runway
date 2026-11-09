package io.github.moyalshoham.runway.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.regex.Pattern;

/** Strict parsing of the values found in bank and accounting exports. */
final class Values {

    static final LocalDate MIN_DATE = LocalDate.of(1990, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    /** ISO first; otherwise day-first, as Israeli exports are, with four- or two-digit years. */
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uu").withResolverStyle(ResolverStyle.STRICT));

    /**
     * Up to 13 integer digits (under one trillion), optional comma thousands separators, up to two
     * decimals. The digit bound matters: BigDecimal parsing is quadratic in the number of digits, so
     * an unbounded pattern let a single huge field pin a CPU.
     */
    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,3}(,\\d{3}){1,4}|\\d{1,13})(\\.\\d{1,2})?$");

    static final int MAX_DATE_LENGTH = 20;
    static final int MAX_AMOUNT_LENGTH = 40;

    private static final Pattern CURRENCY = Pattern.compile("(₪|ILS|NIS|ש\"ח|ש״ח)", Pattern.CASE_INSENSITIVE);

    private Values() {}

    static LocalDate date(String raw, String field) {
        String s = raw.strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException(field + " is empty");
        }
        if (s.length() > MAX_DATE_LENGTH) {
            throw new IllegalArgumentException(field + " is not a date: " + truncate(s));
        }
        for (DateTimeFormatter f : DATES) {
            try {
                LocalDate d = LocalDate.parse(s, f);
                if (d.isBefore(MIN_DATE) || d.isAfter(MAX_DATE)) {
                    throw new IllegalArgumentException(field + " out of range: " + s);
                }
                return d;
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException(
                field + " is not a date (use YYYY-MM-DD, DD/MM/YYYY or DD/MM/YY): " + truncate(s));
    }

    /**
     * Parses "1,234.56", "-1234.5", "(1,234.56)" and the trailing-minus "1,234.56-" some banks use.
     * More than two decimals is an error, not something to round silently.
     */
    static BigDecimal amount(String raw, String field) {
        if (raw.length() > MAX_AMOUNT_LENGTH) {
            throw new IllegalArgumentException(field + " is not an amount: " + truncate(raw));
        }
        String s = CURRENCY.matcher(raw)
                .replaceAll("")
                .replace(" ", "")
                .replace(" ", "")
                .strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException(field + " is empty");
        }
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        } else if (s.startsWith("-") || s.startsWith("−")) {
            negative = true;
            s = s.substring(1);
        } else if (s.endsWith("-")) {
            negative = true;
            s = s.substring(0, s.length() - 1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (!NUMBER.matcher(s).matches()) {
            throw new IllegalArgumentException(field + " is not an amount: " + truncate(raw));
        }
        BigDecimal v = new BigDecimal(s.replace(",", "")).setScale(2);
        if (v.abs().compareTo(MAX_AMOUNT) >= 0) {
            throw new IllegalArgumentException(field + " is too large: " + truncate(raw));
        }
        return negative ? v.negate() : v;
    }

    static String text(String raw, String field, int maxLength, boolean required) {
        String s = raw == null ? "" : raw.strip();
        if (required && s.isEmpty()) {
            throw new IllegalArgumentException(field + " is empty");
        }
        if (s.length() > maxLength) {
            throw new IllegalArgumentException(field + " is longer than " + maxLength + " characters");
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                throw new IllegalArgumentException(field + " contains control characters");
            }
        }
        return s;
    }

    /** Empty, a dash, or zero: the unused side of a debit/credit pair. */
    static boolean blankOrZero(String raw) {
        String s = raw.strip();
        if (s.isEmpty() || s.equals("-")) {
            return true;
        }
        try {
            return amount(s, "value").signum() == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String truncate200(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    static String truncate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
