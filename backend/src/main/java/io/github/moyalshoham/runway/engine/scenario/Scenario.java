package io.github.moyalshoham.runway.engine.scenario;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A "what if" change applied on top of the baseline forecast. */
public sealed interface Scenario
        permits Scenario.CustomerPaysLate, Scenario.LoseCustomer, Scenario.NewHire, Scenario.OneOff {

    /** Every invoice from {@code customer} (open and future) is paid {@code extraDays} later than modelled. */
    record CustomerPaysLate(String customer, int extraDays) implements Scenario {
        public CustomerPaysLate {
            Objects.requireNonNull(customer, "customer");
            if (extraDays < 1 || extraDays > 365) {
                throw new IllegalArgumentException("extraDays must be 1..365");
            }
        }
    }

    /** No new invoices or recurring income from {@code customer} from {@code from} on; open invoices are still paid. */
    record LoseCustomer(String customer, LocalDate from) implements Scenario {
        public LoseCustomer {
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(from, "from");
        }
    }

    /**
     * A new employee from {@code startDate} costing {@code monthlyCost} a month (salary plus employer
     * costs). Israeli payroll: a month's salary is paid by the {@code payDay} of the next month. The
     * first month is prorated by calendar days.
     */
    record NewHire(LocalDate startDate, BigDecimal monthlyCost, int payDay, String label) implements Scenario {
        public NewHire {
            Objects.requireNonNull(startDate, "startDate");
            monthlyCost = Money.round(monthlyCost);
            if (monthlyCost.signum() <= 0) {
                throw new IllegalArgumentException("monthlyCost must be positive");
            }
            if (payDay < 1 || payDay > 28) {
                throw new IllegalArgumentException("payDay must be 1..28");
            }
            label = label == null || label.isBlank() ? "New hire" : label.strip();
        }
    }

    /** A one-off payment ({@code amount < 0}) or receipt ({@code amount > 0}) on {@code date}. */
    record OneOff(LocalDate date, BigDecimal amount, String label) implements Scenario {
        public OneOff {
            Objects.requireNonNull(date, "date");
            amount = Money.round(amount);
            if (amount.signum() == 0) {
                throw new IllegalArgumentException("amount must not be zero");
            }
            label = label == null || label.isBlank() ? "One-off" : label.strip();
        }
    }
}
