package io.github.moyalshoham.runway.engine.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * VAT reporting setup. Israeli defaults: 18% VAT, reported and paid every two months, by the 15th of
 * the month after the period.
 *
 * @param counterpartyPattern matched against the normalised counterparty to find past VAT payments
 */
public record VatSettings(BigDecimal rate, int periodMonths, int paymentDayOfMonth, Pattern counterpartyPattern) {

    public static final Pattern DEFAULT_PATTERN = Pattern.compile("(?U)\\b(vat|מעמ|מס ערך מוסף)\\b");

    public VatSettings {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(counterpartyPattern, "counterpartyPattern");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("VAT rate must be in [0, 1): " + rate);
        }
        if (periodMonths != 1 && periodMonths != 2) {
            throw new IllegalArgumentException("VAT period must be 1 or 2 months: " + periodMonths);
        }
        if (paymentDayOfMonth < 1 || paymentDayOfMonth > 28) {
            throw new IllegalArgumentException("VAT payment day must be 1..28: " + paymentDayOfMonth);
        }
    }

    public static VatSettings israel() {
        return new VatSettings(new BigDecimal("0.18"), 2, 15, DEFAULT_PATTERN);
    }
}
