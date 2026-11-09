package io.github.moyalshoham.runway.engine.forecast;

import static io.github.moyalshoham.runway.engine.Fixtures.invoice;
import static io.github.moyalshoham.runway.engine.Fixtures.monthly;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.CompanySnapshot;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.model.CashModel;
import io.github.moyalshoham.runway.engine.model.ForecastSettings;
import io.github.moyalshoham.runway.engine.scenario.Scenario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ForecasterTest {

    private static final LocalDate OPENING = LocalDate.parse("2024-12-31");
    private static final LocalDate AS_OF = LocalDate.parse("2025-12-20");
    private final Forecaster forecaster = new Forecaster();

    /** Rent of 8,000 on the 1st and a 10,000 retainer on the 5th, every month of 2025. */
    private static List<BankTransaction> steadyBusiness() {
        List<BankTransaction> txs = new ArrayList<>();
        txs.addAll(monthly(YearMonth.of(2025, 1), 12, 1, "-8000", "Landlord"));
        txs.addAll(monthly(YearMonth.of(2025, 1), 12, 5, "10000", "Client A"));
        return txs;
    }

    private static CompanySnapshot snapshot(List<BankTransaction> txs, List<Document> docs) {
        return CompanySnapshot.of(AS_OF, OPENING, new BigDecimal("30000"), txs, docs);
    }

    private static BigDecimal at(Forecast f, String date) {
        return f.days().stream()
                .filter(d -> d.date().equals(LocalDate.parse(date)))
                .findFirst()
                .orElseThrow()
                .p50();
    }

    @Test
    void deterministicRecurringItemsGiveAnExactForecast() {
        Forecast f =
                forecaster.forecast(snapshot(steadyBusiness(), List.of()), ForecastSettings.defaults(BigDecimal.ZERO));

        assertThat(f.startingBalance()).isEqualByComparingTo("54000");
        assertThat(at(f, "2025-12-31")).isEqualByComparingTo("54000");
        assertThat(at(f, "2026-01-01")).isEqualByComparingTo("46000");
        assertThat(at(f, "2026-01-05")).isEqualByComparingTo("56000");
        // 1 Feb 2026 is a Sunday (a business day in Israel).
        assertThat(at(f, "2026-02-01")).isEqualByComparingTo("48000");
        assertThat(f.days()).hasSize(90).allSatisfy(d -> {
            assertThat(d.p10()).isEqualByComparingTo(d.p50());
            assertThat(d.p90()).isEqualByComparingTo(d.p50());
        });
        assertThat(f.recurring()).hasSize(2);
        assertThat(f.alerts()).isEmpty();
        assertThat(f.diagnostics().recurring()).isEqualTo(24);
    }

    @Test
    void alertGivesDateShortfallAndDrivers() {
        Forecast f = forecaster.forecast(
                snapshot(steadyBusiness(), List.of()), ForecastSettings.defaults(new BigDecimal("50000")));

        assertThat(f.alerts()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo(Alert.Kind.BELOW_MINIMUM);
            assertThat(a.severity()).isEqualTo(Alert.Severity.LIKELY);
            assertThat(a.probability()).isEqualTo(1.0);
            assertThat(a.date()).isEqualTo(LocalDate.parse("2026-01-01"));
            assertThat(a.shortfall()).isEqualByComparingTo("4000");
            assertThat(a.lowestBalance()).isEqualByComparingTo("46000");
            assertThat(a.drivers()).first().satisfies(d -> {
                assertThat(d.label()).isEqualTo("Landlord");
                assertThat(d.amount()).isEqualByComparingTo("-8000");
            });
            assertThat(a.message()).contains("below your minimum of ILS 50,000").contains("ILS 4,000");
        });
        assertThat(f.breachProbabilityMinimum()).isEqualTo(1.0);
        assertThat(f.breachProbabilityZero()).isZero();
    }

    @Test
    void openInvoiceArrivesAfterTheCustomersUsualDelay() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LocalDate due = LocalDate.parse("2025-01-15").plusMonths(i);
            docs.add(invoice(
                    "H" + i,
                    "Hapoalim Tech",
                    due.minusDays(30).toString(),
                    due.toString(),
                    "5900",
                    due.plusDays(20).toString()));
        }
        docs.add(invoice("OPEN-1", "Hapoalim Tech", "2025-12-01", "2025-12-31", "23600", null));
        Forecast f = forecaster.forecast(snapshot(steadyBusiness(), docs), ForecastSettings.defaults(BigDecimal.ZERO));

        ScheduleItem item = f.schedule().stream()
                .filter(i -> i.key().equals("open:INVOICE:OPEN-1"))
                .findFirst()
                .orElseThrow();
        // Due 31 Dec, usually 20 days late (±2 days of smoothing, then the next business day).
        assertThat(item.date()).isBetween(LocalDate.parse("2026-01-18"), LocalDate.parse("2026-01-25"));
        assertThat(item.amount()).isEqualByComparingTo("23600");
        assertThat(f.componentTotals().get(Component.OPEN_INVOICES)).isEqualByComparingTo("23600");
    }

    @Test
    void oneOffAndNewHireScenariosChangeTheBalanceFromTheirDates() {
        CashModel model =
                CashModel.build(snapshot(steadyBusiness(), List.of()), ForecastSettings.defaults(BigDecimal.ZERO));
        Forecast base = forecaster.forecast(model, List.of());
        Forecast what = forecaster.forecast(
                model,
                List.of(
                        new Scenario.OneOff(LocalDate.parse("2026-01-15"), new BigDecimal("-20000"), "Laptop"),
                        new Scenario.NewHire(LocalDate.parse("2026-01-15"), new BigDecimal("15500"), 9, "Developer")));

        assertThat(at(what, "2026-01-14")).isEqualByComparingTo(at(base, "2026-01-14"));
        assertThat(at(base, "2026-01-15").subtract(at(what, "2026-01-15"))).isEqualByComparingTo("20000");
        // January is prorated: 17 of 31 days -> 8,500, paid 9 Feb. February in full, paid 9 Mar.
        assertThat(at(base, "2026-02-09").subtract(at(what, "2026-02-09"))).isEqualByComparingTo("28500");
        assertThat(at(base, "2026-03-09").subtract(at(what, "2026-03-09"))).isEqualByComparingTo("44000");
    }

    @Test
    void losingACustomerStopsItsRecurringIncome() {
        CashModel model =
                CashModel.build(snapshot(steadyBusiness(), List.of()), ForecastSettings.defaults(BigDecimal.ZERO));
        Forecast base = forecaster.forecast(model, List.of());
        Forecast lost = forecaster.forecast(
                model, List.of(new Scenario.LoseCustomer("client a", LocalDate.parse("2026-02-01"))));

        assertThat(at(lost, "2026-01-31")).isEqualByComparingTo(at(base, "2026-01-31"));
        assertThat(at(base, "2026-03-10").subtract(at(lost, "2026-03-10"))).isEqualByComparingTo("20000");
    }

    @Test
    void lateCustomerScenarioDelaysOnlyThatCustomersInvoices() {
        List<Document> docs = new ArrayList<>();
        docs.add(invoice("A-1", "Acme", "2025-12-01", "2025-12-25", "11800", null));
        docs.add(invoice("B-1", "Beta", "2025-12-01", "2025-12-25", "5900", null));
        CashModel model = CashModel.build(snapshot(steadyBusiness(), docs), ForecastSettings.defaults(BigDecimal.ZERO));

        Forecast base = forecaster.forecast(model, List.of());
        Forecast late = forecaster.forecast(model, List.of(new Scenario.CustomerPaysLate("ACME", 30)));

        LocalDate acmeBase = item(base, "open:INVOICE:A-1").date();
        LocalDate acmeLate = item(late, "open:INVOICE:A-1").date();
        assertThat(acmeLate).isAfterOrEqualTo(acmeBase.plusDays(28));
        assertThat(item(late, "open:INVOICE:B-1").date())
                .isEqualTo(item(base, "open:INVOICE:B-1").date());
    }

    @Test
    void sameSeedSameForecastAndScenariosShareRandomness() {
        List<BankTransaction> txs = new ArrayList<>(steadyBusiness());
        Random random = new Random(11);
        for (LocalDate d = LocalDate.parse("2025-01-02"); !d.isAfter(AS_OF); d = d.plusDays(1)) {
            if (random.nextDouble() < 0.4) {
                txs.add(new BankTransaction(
                        d, BigDecimal.valueOf(-(20 + random.nextInt(600))), "Card purchase " + random.nextInt(50), ""));
            }
        }
        CashModel model = CashModel.build(snapshot(txs, List.of()), ForecastSettings.defaults(BigDecimal.ZERO));

        Forecast a = forecaster.forecast(model, List.of());
        Forecast b = forecaster.forecast(model, List.of());
        Forecast withOneOff = forecaster.forecast(
                model, List.of(new Scenario.OneOff(LocalDate.parse("2026-02-01"), new BigDecimal("-5000"), "Repair")));

        assertThat(a.days()).isEqualTo(b.days());
        assertThat(a.days().getFirst().p90())
                .isGreaterThan(a.days().getFirst().p10().subtract(BigDecimal.ONE));
        assertThat(a.days().getLast().p90()).isGreaterThan(a.days().getLast().p10());
        // Before the one-off, the scenario paths are identical to the baseline paths.
        for (int h = 0; h < 42; h++) {
            assertThat(withOneOff.days().get(h)).isEqualTo(a.days().get(h));
        }
        assertThat(a.days().getLast().p50().subtract(withOneOff.days().getLast().p50()))
                .isEqualByComparingTo("5000");
    }

    @Test
    void doubtfulInvoicesAreExcluded() {
        List<Document> docs = List.of(invoice("OLD", "Gone Ltd", "2025-03-01", "2025-04-01", "50000", null));
        Forecast f = forecaster.forecast(snapshot(steadyBusiness(), docs), ForecastSettings.defaults(BigDecimal.ZERO));

        assertThat(f.doubtfulInvoices()).extracting(Document::number).containsExactly("OLD");
        assertThat(f.componentTotals().get(Component.OPEN_INVOICES)).isEqualByComparingTo("0");
    }

    private static ScheduleItem item(Forecast f, String key) {
        return f.schedule().stream()
                .filter(i -> i.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
