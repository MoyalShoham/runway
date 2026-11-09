package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Invoices or bills that will be issued during the horizon but are not part of a recurring series
 * (project work, ad-hoc supplier orders).
 *
 * <p>Arrivals are a Poisson process whose rate is the recent, de-seasonalised issue rate times the
 * seasonal factor of the target month. The rate itself is uncertain (it was estimated from a few
 * dozen documents), so each path first draws its own rate from the Gamma posterior of the observed
 * count. Each new document copies a recent real one (counterparty, amount, payment terms), so a large
 * client's invoices stay large and keep that client's payment habits.
 */
public final class AdHocDocumentModel {

    static final int TEMPLATE_LOOKBACK_DAYS = 365;

    /** A past document used as a template for future ones. */
    public record Template(String counterpartyKey, String label, BigDecimal amount, int termDays) {}

    /** A simulated future document. */
    public record NewDocument(LocalDate issueDate, Template template) {}

    private final List<Template> templates;
    private final double ratePerDay;
    private final double observedCount;
    private final SeasonalIndex season;

    private AdHocDocumentModel(
            List<Template> templates, double ratePerDay, double observedCount, SeasonalIndex season) {
        this.templates = List.copyOf(templates);
        this.ratePerDay = ratePerDay;
        this.observedCount = observedCount;
        this.season = season;
    }

    public static AdHocDocumentModel fit(List<Document> adHoc, LocalDate asOf, int windowDays) {
        TreeMap<YearMonth, BigDecimal> monthly = new TreeMap<>();
        LocalDate earliest = adHoc.stream()
                .map(Document::issueDate)
                .min(LocalDate::compareTo)
                .orElse(asOf);
        for (YearMonth m = YearMonth.from(earliest); !m.isAfter(YearMonth.from(asOf)); m = m.plusMonths(1)) {
            monthly.put(m, Money.ZERO);
        }
        List<Template> templates = new ArrayList<>();
        int inWindow = 0;
        LocalDate windowStart = asOf.minusDays(windowDays);
        LocalDate templateStart = asOf.minusDays(TEMPLATE_LOOKBACK_DAYS);
        for (Document d : adHoc) {
            // Seasonality is fitted on document counts, because it scales the arrival rate.
            monthly.merge(YearMonth.from(d.issueDate()), BigDecimal.ONE, BigDecimal::add);
            if (d.issueDate().isAfter(windowStart)) {
                inWindow++;
            }
            if (d.issueDate().isAfter(templateStart)) {
                int terms = (int) ChronoUnit.DAYS.between(d.issueDate(), d.dueDate());
                templates.add(new Template(Counterparties.key(d.counterparty()), d.counterparty(), d.amount(), terms));
            }
        }
        boolean enoughHistory = !earliest.isAfter(windowStart);
        SeasonalIndex season = SeasonalIndex.fit(monthly, YearMonth.from(asOf).minusMonths(1));
        double seasonDays = 0;
        for (LocalDate d = windowStart.plusDays(1); !d.isAfter(asOf); d = d.plusDays(1)) {
            seasonDays += season.factor(d.getMonth());
        }
        int observedDays = enoughHistory ? windowDays : (int) ChronoUnit.DAYS.between(earliest, asOf) + 1;
        double denominator = enoughHistory ? seasonDays : Math.max(observedDays, 1);
        double rate = templates.isEmpty() ? 0 : inWindow / denominator;
        return new AdHocDocumentModel(templates, rate, inWindow, season);
    }

    public List<NewDocument> generate(LocalDate asOf, int horizonDays, SplittableRandom rng) {
        List<NewDocument> docs = new ArrayList<>();
        if (templates.isEmpty() || ratePerDay <= 0) {
            return docs;
        }
        // Gamma(n + 1/2) / (n + 1/2): posterior of the rate relative to its point estimate.
        double rateFactor = Gamma.sample(observedCount + 0.5, rng) / (observedCount + 0.5);
        for (int h = 1; h <= horizonDays; h++) {
            LocalDate day = asOf.plusDays(h);
            int n = Poisson.sample(expectedPerDay(day) * rateFactor, rng);
            for (int i = 0; i < n; i++) {
                docs.add(new NewDocument(day, templates.get(rng.nextInt(templates.size()))));
            }
        }
        return docs;
    }

    public double expectedPerDay(LocalDate day) {
        return ratePerDay * season.factor(day.getMonth());
    }
}
