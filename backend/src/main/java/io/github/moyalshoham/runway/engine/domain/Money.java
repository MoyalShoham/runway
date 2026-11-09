package io.github.moyalshoham.runway.engine.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Helpers for money arithmetic. Money is always {@link BigDecimal} with two decimal places and
 * banker's rounding; doubles are only used for probabilities and relative factors, and are
 * converted with {@link BigDecimal#valueOf(double)} before touching an amount.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private Money() {}

    public static BigDecimal of(String value) {
        return round(new BigDecimal(value));
    }

    public static BigDecimal of(long wholeUnits) {
        return round(BigDecimal.valueOf(wholeUnits));
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    /** {@code amount * factor}, rounded to agorot. */
    public static BigDecimal times(BigDecimal amount, double factor) {
        if (!Double.isFinite(factor)) {
            throw new IllegalArgumentException("factor must be finite: " + factor);
        }
        return round(amount.multiply(BigDecimal.valueOf(factor)));
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal total = ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return round(total);
    }

    /** The VAT part of a VAT-inclusive (gross) amount: {@code gross * rate / (1 + rate)}. */
    public static BigDecimal vatPortion(BigDecimal gross, BigDecimal rate) {
        return gross.multiply(rate).divide(BigDecimal.ONE.add(rate), SCALE, ROUNDING);
    }

    public static BigDecimal median(java.util.List<BigDecimal> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("median of empty list");
        }
        java.util.List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return round(sorted.get(n / 2));
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), SCALE, ROUNDING);
    }
}
