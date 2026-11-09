package io.github.moyalshoham.runway.engine.forecast;

/** The parts a forecast is built from; every simulated flow belongs to exactly one. */
public enum Component {
    OPEN_INVOICES("Open invoices"),
    OPEN_BILLS("Open bills"),
    RECURRING("Recurring items"),
    FUTURE_INVOICES("Invoices not yet issued"),
    FUTURE_BILLS("Bills not yet received"),
    IRREGULAR("Irregular flows"),
    VAT("VAT"),
    SCENARIO("Scenario changes");

    private final String label;

    Component(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
