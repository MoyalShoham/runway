package io.github.moyalshoham.runway.engine.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Israeli banking week: Sunday to Thursday. Payments scheduled for Friday or Saturday move to the
 * following Sunday. Public holidays are not modelled (see the README's limitations).
 */
public final class BusinessCalendar {

    private BusinessCalendar() {}

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.FRIDAY && d != DayOfWeek.SATURDAY;
    }

    public static LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
