package io.github.moyalshoham.runway.engine.recurring;

import io.github.moyalshoham.runway.engine.domain.BusinessCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A detected recurring flow.
 *
 * @param key grouping key (normalised counterparty, plus an amount-cluster suffix if split)
 * @param label human-readable name taken from the most common raw counterparty
 * @param sign +1 for money in, -1 for money out
 * @param anchor month-based cadences only: the day of month (1..28), or 0 for the last day, -1 for
 *     the day before it, and so on
 * @param amount the amount expected next time (median of the last three occurrences)
 * @param relativeAmountDeviations past deviations of each amount from the one predicted for it,
 *     resampled to put noise on future amounts
 * @param dateResiduals past deviations (days) from the nominal date, resampled the same way
 * @param regularity share of intervals that matched the cadence (skips count half)
 * @param confidence heuristic 0..1 combining regularity, history length and amount stability
 * @param active false when the flow seems to have stopped (no occurrence for 1.5 periods)
 * @param amountChange the last step change in amount, or null
 */
public record RecurringPattern(
        String key,
        String label,
        int sign,
        Cadence cadence,
        int anchor,
        BigDecimal amount,
        List<Double> relativeAmountDeviations,
        List<Integer> dateResiduals,
        LocalDate firstSeen,
        LocalDate lastSeen,
        int occurrences,
        double regularity,
        double confidence,
        boolean active,
        AmountChange amountChange) {

    public RecurringPattern {
        relativeAmountDeviations = List.copyOf(relativeAmountDeviations);
        dateResiduals = List.copyOf(dateResiduals);
    }

    /** Nominal (business-day adjusted) dates of the next occurrences, from the one after lastSeen up to {@code until}. */
    public List<LocalDate> nominalDatesAfterLastSeen(LocalDate until) {
        List<LocalDate> dates = new ArrayList<>();
        if (cadence.isMonthBased()) {
            YearMonth base = nominalMonth(lastSeen, anchor);
            for (int k = 1; ; k++) {
                LocalDate d = anchorDate(base.plusMonths((long) k * cadence.months()), anchor);
                if (d.isAfter(until)) {
                    break;
                }
                dates.add(d);
            }
        } else {
            for (int k = 1; ; k++) {
                LocalDate d = BusinessCalendar.nextBusinessDay(lastSeen.plusDays((long) k * cadence.days()));
                if (d.isAfter(until)) {
                    break;
                }
                dates.add(d);
            }
        }
        return dates;
    }

    /** The counterparty part of {@link #key()}, without the amount-cluster suffix. */
    public String counterpartyKey() {
        int hash = key.lastIndexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    public LocalDate nextNominalDate() {
        return nominalDatesAfterLastSeen(lastSeen.plusYears(2)).getFirst();
    }

    /** Day of month for {@code anchor} in {@code month}, moved to the next business day. */
    public static LocalDate anchorDate(YearMonth month, int anchor) {
        int len = month.lengthOfMonth();
        int day = anchor >= 1 ? Math.min(anchor, len) : Math.max(1, len + anchor);
        return BusinessCalendar.nextBusinessDay(month.atDay(day));
    }

    /** Encodes a date's day of month as an anchor: days 1..20 as-is, later days relative to month end. */
    public static int anchorOf(LocalDate date) {
        int day = date.getDayOfMonth();
        return day <= 20 ? day : day - date.lengthOfMonth();
    }

    /** The month whose anchor date is closest to {@code date} (a payment can slip into the next month). */
    public static YearMonth nominalMonth(LocalDate date, int anchor) {
        YearMonth m = YearMonth.from(date);
        YearMonth best = m;
        long bestDistance = Long.MAX_VALUE;
        for (YearMonth candidate : List.of(m.minusMonths(1), m, m.plusMonths(1))) {
            long distance = Math.abs(ChronoUnit.DAYS.between(anchorDate(candidate, anchor), date));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
