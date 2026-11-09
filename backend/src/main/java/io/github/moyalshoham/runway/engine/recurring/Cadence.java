package io.github.moyalshoham.runway.engine.recurring;

/**
 * Candidate recurrence periods. Month-based cadences step by calendar months (so "the 1st of the
 * month" stays the 1st); day-based ones step by a fixed number of days.
 */
public enum Cadence {
    WEEKLY(7, 0, 2, 4),
    BIWEEKLY(14, 0, 3, 4),
    MONTHLY(0, 1, 5, 3),
    BIMONTHLY(0, 2, 7, 3),
    QUARTERLY(0, 3, 10, 3),
    YEARLY(0, 12, 15, 2);

    private final int days;
    private final int months;
    private final int toleranceDays;
    private final int minOccurrences;

    Cadence(int days, int months, int toleranceDays, int minOccurrences) {
        this.days = days;
        this.months = months;
        this.toleranceDays = toleranceDays;
        this.minOccurrences = minOccurrences;
    }

    public boolean isMonthBased() {
        return months > 0;
    }

    public int days() {
        return days;
    }

    public int months() {
        return months;
    }

    /** How far (in days) an occurrence may drift from the nominal date and still count. */
    public int toleranceDays() {
        return toleranceDays;
    }

    public int minOccurrences() {
        return minOccurrences;
    }

    public double nominalDays() {
        return isMonthBased() ? months * 30.4375 : days;
    }
}
