package io.github.moyalshoham.runway.engine.domain;

/** An invoice is money the business is owed; a bill is money it owes. */
public enum DocumentType {
    INVOICE(1),
    BILL(-1);

    private final int cashSign;

    DocumentType(int cashSign) {
        this.cashSign = cashSign;
    }

    /** +1 when settling the document brings cash in, -1 when it takes cash out. */
    public int cashSign() {
        return cashSign;
    }
}
