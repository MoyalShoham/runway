package io.github.moyalshoham.runway.engine.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * VAT reporting setup. Israeli defaults: reported and paid every two months, by the 15th of the month
 * after the period, at the rate in force on the document date (17% until the end of 2024, 18% from 1
 * January 2025).
 *
 * @param rates VAT rate by the date it took effect
 * @param counterpartyPattern matched against the normalised counterparty to find past VAT payments
 */
public record VatSettings(
        NavigableMap<LocalDate, BigDecimal> rates,
        int periodMonths,
        int paymentDayOfMonth,
        Pattern counterpartyPattern) {

    public static final Pattern DEFAULT_PATTERN = Pattern.compile("(?U)\\b(vat|מעמ|מס ערך מוסף)\\b");

    /** Israel's standard VAT rate since 2012. */
    public static final NavigableMap<LocalDate, BigDecimal> ISRAEL_RATES = new TreeMap<>(Map.of(
            LocalDate.of(2012, 9, 1), new BigDecimal("0.17"),
            LocalDate.of(2013, 6, 2), new BigDecimal("0.18"),
            LocalDate.of(2015, 10, 1), new BigDecimal("0.17"),
            LocalDate.of(2025, 1, 1), new BigDecimal("0.18")));

    public VatSettings {
        Objects.requireNonNull(rates, "rates");
        Objects.requireNonNull(counterpartyPattern, "counterpartyPattern");
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("at least one VAT rate is needed");
        }
        for (BigDecimal rate : rates.values()) {
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("VAT rate must be in [0, 1): " + rate);
            }
        }
        rates = java.util.Collections.unmodifiableNavigableMap(new TreeMap<>(rates));
        if (periodMonths != 1 && periodMonths != 2) {
            throw new IllegalArgumentException("VAT period must be 1 or 2 months: " + periodMonths);
        }
        if (paymentDayOfMonth < 1 || paymentDayOfMonth > 28) {
            throw new IllegalArgumentException("VAT payment day must be 1..28: " + paymentDayOfMonth);
        }
    }

    public static VatSettings israel() {
        return new VatSettings(ISRAEL_RATES, 2, 15, DEFAULT_PATTERN);
    }

    /** The rate in force on {@code date}; the earliest known rate for older dates. */
    public BigDecimal rateOn(LocalDate date) {
        Map.Entry<LocalDate, BigDecimal> e = rates.floorEntry(date);
        return e != null ? e.getValue() : rates.firstEntry().getValue();
    }
}
