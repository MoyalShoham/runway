package io.github.moyalshoham.runway.engine.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A booked bank transaction. {@code amount} is signed: positive is money in, negative is money
 * out. {@code counterparty} may be empty when the bank export only has a free-text description.
 */
public record BankTransaction(LocalDate date, BigDecimal amount, String description, String counterparty) {

    public BankTransaction {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        amount = Money.round(amount);
        description = description == null ? "" : description.strip();
        counterparty = counterparty == null ? "" : counterparty.strip();
    }

    public boolean isInflow() {
        return amount.signum() > 0;
    }

    /** Counterparty if present, otherwise the description; used for grouping. */
    public String party() {
        return counterparty.isEmpty() ? description : counterparty;
    }
}
