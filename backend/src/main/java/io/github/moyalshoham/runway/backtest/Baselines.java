package io.github.moyalshoham.runway.backtest;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/** Naive forecasts the engine is compared against. */
public final class Baselines {

    private Baselines() {}

    /** "Balance stays flat": every future day equals today's balance. */
    public static BigDecimal[] flat(BigDecimal balance, int horizon) {
        BigDecimal[] path = new BigDecimal[horizon];
        Arrays.fill(path, balance);
        return path;
    }

    /** "Last 30 days repeated": the daily net flows of the last 30 days, replayed in a loop. */
    public static BigDecimal[] lastThirtyDaysRepeated(
            BigDecimal balance, List<BankTransaction> history, LocalDate asOf, int horizon) {
        BigDecimal[] daily = new BigDecimal[30];
        Arrays.fill(daily, Money.ZERO);
        LocalDate from = asOf.minusDays(29);
        for (BankTransaction t : history) {
            if (!t.date().isBefore(from) && !t.date().isAfter(asOf)) {
                int i = (int) ChronoUnit.DAYS.between(from, t.date());
                daily[i] = daily[i].add(t.amount());
            }
        }
        BigDecimal[] path = new BigDecimal[horizon];
        BigDecimal b = balance;
        for (int h = 0; h < horizon; h++) {
            b = b.add(daily[h % 30]);
            path[h] = b;
        }
        return path;
    }
}
