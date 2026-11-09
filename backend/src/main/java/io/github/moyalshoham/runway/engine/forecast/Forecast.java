package io.github.moyalshoham.runway.engine.forecast;

import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.model.CashModel;
import io.github.moyalshoham.runway.engine.recurring.RecurringPattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A probabilistic balance forecast.
 *
 * @param breachProbabilityMinimum share of paths that dip below the minimum within the horizon
 * @param breachProbabilityZero share of paths that dip below zero within the horizon
 * @param componentTotals expected net flow of each component over the horizon
 */
public record Forecast(
        LocalDate asOf,
        BigDecimal startingBalance,
        BigDecimal minimumBalance,
        int paths,
        List<DailyPoint> days,
        Map<Component, BigDecimal> componentTotals,
        List<ScheduleItem> schedule,
        List<Alert> alerts,
        double breachProbabilityMinimum,
        double breachProbabilityZero,
        List<RecurringPattern> recurring,
        List<CashModel.RecurringDocuments> recurringDocuments,
        List<Document> doubtfulInvoices,
        List<String> customers,
        CashModel.Diagnostics diagnostics) {

    public Forecast {
        days = List.copyOf(days);
        componentTotals = Map.copyOf(componentTotals);
        schedule = List.copyOf(schedule);
        alerts = List.copyOf(alerts);
        recurring = List.copyOf(recurring);
        recurringDocuments = List.copyOf(recurringDocuments);
        doubtfulInvoices = List.copyOf(doubtfulInvoices);
        customers = List.copyOf(customers);
    }

    /** Median balance {@code h} days after asOf (1-based). */
    public BigDecimal medianAt(int h) {
        return days.get(h - 1).p50();
    }
}
