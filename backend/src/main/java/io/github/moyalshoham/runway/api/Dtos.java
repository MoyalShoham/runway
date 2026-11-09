package io.github.moyalshoham.runway.api;

import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.forecast.Alert;
import io.github.moyalshoham.runway.engine.forecast.Component;
import io.github.moyalshoham.runway.engine.forecast.Forecast;
import io.github.moyalshoham.runway.engine.model.CashModel;
import io.github.moyalshoham.runway.engine.recurring.RecurringPattern;
import io.github.moyalshoham.runway.engine.scenario.Scenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request and response bodies of the REST API. Money is a decimal string in JSON. */
public final class Dtos {

    private Dtos() {}

    public record CompanyRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Digits(integer = 13, fraction = 2) BigDecimal openingBalance,
            @NotNull LocalDate openingDate,
            @NotNull @Digits(integer = 13, fraction = 2) BigDecimal minimumBalance) {}

    public record CompanyUpdate(
            @Size(min = 1, max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
            @Digits(integer = 13, fraction = 2) BigDecimal minimumBalance) {}

    public record CompanyResponse(
            long id,
            String name,
            String currency,
            BigDecimal openingBalance,
            LocalDate openingDate,
            BigDecimal minimumBalance,
            long transactions,
            LocalDate lastTransactionDate) {}

    public record ScenarioItem(
            @NotNull Type type,
            @Size(max = 200) String customer,
            @Min(1) @Max(365) Integer extraDays,
            LocalDate date,
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Min(1) @Max(28) Integer payDay,
            @Size(max = 100) String label) {

        public enum Type {
            CUSTOMER_PAYS_LATE,
            LOSE_CUSTOMER,
            NEW_HIRE,
            ONE_OFF
        }

        /** Converts to the engine's scenario, rejecting missing fields with a clear message. */
        Scenario toScenario() {
            return switch (type) {
                case CUSTOMER_PAYS_LATE ->
                    new Scenario.CustomerPaysLate(required(customer, "customer"), required(extraDays, "extraDays"));
                case LOSE_CUSTOMER -> new Scenario.LoseCustomer(required(customer, "customer"), required(date, "date"));
                case NEW_HIRE ->
                    new Scenario.NewHire(
                            required(date, "date"), required(amount, "amount"), payDay == null ? 9 : payDay, label);
                case ONE_OFF -> new Scenario.OneOff(required(date, "date"), required(amount, "amount"), label);
            };
        }

        private <T> T required(T value, String field) {
            if (value == null || (value instanceof String s && s.isBlank())) {
                throw new IllegalArgumentException(type + " needs " + field);
            }
            return value;
        }
    }

    public record ScenarioRequest(
            LocalDate asOf,
            @Min(7) @Max(180) Integer horizonDays,
            @NotNull @Size(min = 1, max = 20) List<@Valid @NotNull ScenarioItem> scenarios) {}

    public record ImportResponse(int rowsRead, int imported, int duplicates, int updated, List<RowErrorDto> rejected) {}

    public record RowErrorDto(long line, String message) {}

    // ---- forecast -----------------------------------------------------------------------------

    public record DayDto(
            LocalDate date, BigDecimal p10, BigDecimal p50, BigDecimal p90, double probabilityBelowMinimum) {}

    public record DriverDto(String label, String component, LocalDate date, BigDecimal amount, String reason) {}

    public record AlertDto(
            String kind,
            String severity,
            BigDecimal threshold,
            double probability,
            LocalDate date,
            BigDecimal shortfall,
            LocalDate lowestDate,
            BigDecimal lowestBalance,
            String message,
            List<DriverDto> drivers) {}

    public record ScheduleDto(LocalDate date, String label, String component, BigDecimal amount, double probability) {}

    public record ComponentDto(String component, String label, BigDecimal amount) {}

    public record AmountChangeDto(BigDecimal from, BigDecimal to, LocalDate since) {}

    public record RecurringDto(
            String label,
            String direction,
            String cadence,
            BigDecimal amount,
            LocalDate lastSeen,
            LocalDate nextDate,
            int occurrences,
            double confidence,
            boolean active,
            AmountChangeDto amountChange) {}

    public record RecurringDocumentDto(
            String type,
            String label,
            String cadence,
            BigDecimal amount,
            LocalDate nextIssueDate,
            int termDays,
            boolean active) {}

    public record DoubtfulDto(String number, String counterparty, LocalDate dueDate, BigDecimal amount) {}

    public record ForecastResponse(
            LocalDate asOf,
            BigDecimal startingBalance,
            BigDecimal minimumBalance,
            int paths,
            double breachProbabilityMinimum,
            double breachProbabilityZero,
            List<DayDto> days,
            List<AlertDto> alerts,
            List<ScheduleDto> schedule,
            List<ComponentDto> components,
            List<RecurringDto> recurring,
            List<RecurringDocumentDto> recurringDocuments,
            List<DoubtfulDto> doubtfulInvoices,
            List<String> customers,
            CashModel.Diagnostics diagnostics) {

        static final int MAX_SCHEDULE = 200;

        static ForecastResponse of(Forecast f) {
            return new ForecastResponse(
                    f.asOf(),
                    f.startingBalance(),
                    f.minimumBalance(),
                    f.paths(),
                    f.breachProbabilityMinimum(),
                    f.breachProbabilityZero(),
                    f.days().stream()
                            .map(d -> new DayDto(d.date(), d.p10(), d.p50(), d.p90(), d.probabilityBelowMinimum()))
                            .toList(),
                    f.alerts().stream().map(ForecastResponse::alert).toList(),
                    f.schedule().stream()
                            .limit(MAX_SCHEDULE)
                            .map(i -> new ScheduleDto(
                                    i.date(), i.label(), i.component().name(), i.amount(), i.probability()))
                            .toList(),
                    java.util.Arrays.stream(Component.values())
                            .map(c -> new ComponentDto(
                                    c.name(), c.label(), f.componentTotals().get(c)))
                            .toList(),
                    f.recurring().stream().map(ForecastResponse::recurring).toList(),
                    f.recurringDocuments().stream()
                            .map(ForecastResponse::recurringDocument)
                            .toList(),
                    f.doubtfulInvoices().stream()
                            .map(ForecastResponse::doubtful)
                            .toList(),
                    f.customers(),
                    f.diagnostics());
        }

        private static AlertDto alert(Alert a) {
            return new AlertDto(
                    a.kind().name(),
                    a.severity().name(),
                    a.threshold(),
                    a.probability(),
                    a.date(),
                    a.shortfall(),
                    a.lowestDate(),
                    a.lowestBalance(),
                    a.message(),
                    a.drivers().stream()
                            .map(d -> new DriverDto(d.label(), d.component().name(), d.date(), d.amount(), d.reason()))
                            .toList());
        }

        private static RecurringDto recurring(RecurringPattern p) {
            return new RecurringDto(
                    p.label(),
                    p.sign() > 0 ? "IN" : "OUT",
                    p.cadence().name(),
                    p.amount(),
                    p.lastSeen(),
                    p.nextNominalDate(),
                    p.occurrences(),
                    p.confidence(),
                    p.active(),
                    p.amountChange() == null
                            ? null
                            : new AmountChangeDto(
                                    p.amountChange().from(),
                                    p.amountChange().to(),
                                    p.amountChange().since()));
        }

        private static RecurringDocumentDto recurringDocument(CashModel.RecurringDocuments d) {
            RecurringPattern p = d.pattern();
            return new RecurringDocumentDto(
                    d.type().name(),
                    p.label(),
                    p.cadence().name(),
                    p.amount(),
                    p.nextNominalDate(),
                    d.termDays(),
                    p.active());
        }

        private static DoubtfulDto doubtful(Document d) {
            return new DoubtfulDto(d.number(), d.counterparty(), d.dueDate(), d.amount());
        }
    }

    public record ComparisonResponse(
            ForecastResponse baseline,
            ForecastResponse scenario,
            BigDecimal endDifference,
            BigDecimal lowestBaseline,
            BigDecimal lowestScenario) {}

    public record BalanceDto(LocalDate date, BigDecimal balance) {}

    public record TransactionDto(long id, LocalDate date, BigDecimal amount, String description, String counterparty) {}

    public record DocumentDto(
            String type,
            String number,
            String counterparty,
            LocalDate issueDate,
            LocalDate dueDate,
            BigDecimal amount,
            LocalDate paidDate) {}
}
