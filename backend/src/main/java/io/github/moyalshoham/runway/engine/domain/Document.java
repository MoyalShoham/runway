package io.github.moyalshoham.runway.engine.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * An invoice (receivable) or bill (payable). {@code amount} is the positive, VAT-inclusive total.
 * {@code paidDate} is null while the document is open.
 */
public record Document(
        DocumentType type,
        String number,
        String counterparty,
        LocalDate issueDate,
        LocalDate dueDate,
        BigDecimal amount,
        LocalDate paidDate) {

    public Document {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(issueDate, "issueDate");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(amount, "amount");
        amount = Money.round(amount);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("document amount must be positive: " + number);
        }
        if (dueDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("due date before issue date: " + number);
        }
    }

    public Optional<LocalDate> paid() {
        return Optional.ofNullable(paidDate);
    }

    /** The cash effect of settling this document: positive for invoices, negative for bills. */
    public BigDecimal signedAmount() {
        return type.cashSign() > 0 ? amount : amount.negate();
    }

    /** This document as it was known on {@code asOf}: a payment after that date is not known yet. */
    public Document asKnownOn(LocalDate asOf) {
        if (paidDate != null && paidDate.isAfter(asOf)) {
            return new Document(type, number, counterparty, issueDate, dueDate, amount, null);
        }
        return this;
    }
}
