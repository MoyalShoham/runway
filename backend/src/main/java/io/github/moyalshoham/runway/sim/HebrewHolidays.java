package io.github.moyalshoham.runway.sim;

import java.time.LocalDate;
import java.util.Map;

/**
 * Approximate holiday windows used for seasonality in the generator: the Tishrei holidays (Rosh
 * Hashana to Simchat Torah) and Pesach. Dates are the first day of each holiday, Gregorian.
 */
final class HebrewHolidays {

    private static final Map<Integer, LocalDate> ROSH_HASHANA = Map.of(
            2022, LocalDate.of(2022, 9, 26),
            2023, LocalDate.of(2023, 9, 16),
            2024, LocalDate.of(2024, 10, 3),
            2025, LocalDate.of(2025, 9, 23),
            2026, LocalDate.of(2026, 9, 12),
            2027, LocalDate.of(2027, 10, 2),
            2028, LocalDate.of(2028, 9, 21),
            2029, LocalDate.of(2029, 9, 10));

    private static final Map<Integer, LocalDate> PESACH = Map.of(
            2022, LocalDate.of(2022, 4, 16),
            2023, LocalDate.of(2023, 4, 6),
            2024, LocalDate.of(2024, 4, 23),
            2025, LocalDate.of(2025, 4, 13),
            2026, LocalDate.of(2026, 4, 2),
            2027, LocalDate.of(2027, 4, 22),
            2028, LocalDate.of(2028, 4, 11),
            2029, LocalDate.of(2029, 3, 31));

    private HebrewHolidays() {}

    static boolean inTishrei(LocalDate d) {
        LocalDate rh = ROSH_HASHANA.get(d.getYear());
        return rh != null && !d.isBefore(rh.minusDays(1)) && !d.isAfter(rh.plusDays(23));
    }

    static boolean inPesach(LocalDate d) {
        LocalDate p = PESACH.get(d.getYear());
        return p != null && !d.isBefore(p.minusDays(1)) && !d.isAfter(p.plusDays(7));
    }

    /** The day before Rosh Hashana or Pesach in this year, if known (holiday gifts are bought before). */
    static LocalDate[] giftDates(int year) {
        LocalDate rh = ROSH_HASHANA.get(year);
        LocalDate p = PESACH.get(year);
        if (rh == null || p == null) {
            return new LocalDate[0];
        }
        return new LocalDate[] {p.minusDays(10), rh.minusDays(10)};
    }
}
