package io.github.moyalshoham.runway.service;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.CompanySnapshot;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.forecast.Forecast;
import io.github.moyalshoham.runway.engine.forecast.Forecaster;
import io.github.moyalshoham.runway.engine.model.CashModel;
import io.github.moyalshoham.runway.engine.model.ForecastSettings;
import io.github.moyalshoham.runway.engine.scenario.Scenario;
import io.github.moyalshoham.runway.persistence.CompanyRow;
import io.github.moyalshoham.runway.persistence.DocumentRepository;
import io.github.moyalshoham.runway.persistence.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Loads a company from the database and runs the engine on it. */
@Service
public class ForecastService {

    public static final int MAX_HISTORY_DAYS = 730;

    /** A forecast with and without scenarios, on the same random numbers. */
    public record Comparison(Forecast baseline, Forecast scenario) {}

    /** Actual closing balance on one day. */
    public record BalancePoint(LocalDate date, BigDecimal balance) {}

    private final CompanyService companies;
    private final TransactionRepository transactions;
    private final DocumentRepository documents;
    private final Forecaster forecaster = new Forecaster();

    public ForecastService(CompanyService companies, TransactionRepository transactions, DocumentRepository documents) {
        this.companies = companies;
        this.transactions = transactions;
        this.documents = documents;
    }

    public Forecast forecast(long companyId, LocalDate asOf, Integer horizonDays) {
        return forecaster.forecast(model(companyId, asOf, horizonDays), List.of());
    }

    public Comparison compare(long companyId, LocalDate asOf, Integer horizonDays, List<Scenario> scenarios) {
        CashModel model = model(companyId, asOf, horizonDays);
        return new Comparison(forecaster.forecast(model, List.of()), forecaster.forecast(model, scenarios));
    }

    /** Actual daily balances for the {@code days} days up to asOf. */
    public List<BalancePoint> history(long companyId, LocalDate asOf, int days) {
        if (days < 1 || days > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be 1.." + MAX_HISTORY_DAYS);
        }
        CompanyRow c = companies.get(companyId);
        List<BankTransaction> txs = transactions.findAll(companyId);
        LocalDate end = resolveAsOf(c, asOf, companyId);
        LocalDate start = end.minusDays(days - 1L);
        if (start.isBefore(c.openingDate())) {
            start = c.openingDate();
        }
        List<BalancePoint> points = new ArrayList<>();
        BigDecimal balance = c.openingBalance();
        int i = 0;
        for (LocalDate d = c.openingDate(); !d.isAfter(end); d = d.plusDays(1)) {
            while (i < txs.size() && !txs.get(i).date().isAfter(d)) {
                if (txs.get(i).date().isAfter(c.openingDate())) {
                    balance = balance.add(txs.get(i).amount());
                }
                i++;
            }
            if (!d.isBefore(start)) {
                points.add(new BalancePoint(d, balance));
            }
        }
        return points;
    }

    private CashModel model(long companyId, LocalDate asOf, Integer horizonDays) {
        CompanyRow c = companies.get(companyId);
        List<BankTransaction> txs = transactions.findAll(companyId);
        List<Document> docs = documents.findAll(companyId);
        LocalDate resolved = resolveAsOf(c, asOf, companyId);
        CompanySnapshot snapshot = CompanySnapshot.of(resolved, c.openingDate(), c.openingBalance(), txs, docs);
        ForecastSettings settings = ForecastSettings.defaults(c.minimumBalance());
        if (horizonDays != null) {
            settings = settings.withHorizon(horizonDays);
        }
        return CashModel.build(snapshot, settings);
    }

    /** asOf defaults to the last booked transaction, and must lie between the opening date and it. */
    private LocalDate resolveAsOf(CompanyRow c, LocalDate asOf, long companyId) {
        LocalDate latest = transactions.latestDate(companyId).orElse(c.openingDate());
        if (asOf == null) {
            return latest;
        }
        if (asOf.isBefore(c.openingDate()) || asOf.isAfter(latest)) {
            throw new IllegalArgumentException("asOf must be between " + c.openingDate() + " and " + latest
                    + " (the last imported transaction); got " + asOf + ", "
                    + ChronoUnit.DAYS.between(latest, asOf) + " days after the data");
        }
        return asOf;
    }
}
