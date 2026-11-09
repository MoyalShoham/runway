package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.CompanySnapshot;
import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.engine.recurring.Occurrence;
import io.github.moyalshoham.runway.engine.recurring.RecurringDetector;
import io.github.moyalshoham.runway.engine.recurring.RecurringPattern;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fitted model of a company's cash flows, split into components that never overlap: each bank
 * transaction is explained by exactly one of document settlement, VAT, a discretionary owner
 * decision (not projected), a recurring pattern or the irregular baseline.
 */
public record CashModel(
        CompanySnapshot snapshot,
        ForecastSettings settings,
        List<RecurringPattern> transactionPatterns,
        List<RecurringDocuments> documentPatterns,
        DelayModel invoiceDelays,
        DelayModel billDelays,
        AdHocDocumentModel adHocInvoices,
        AdHocDocumentModel adHocBills,
        BaselineModel baseline,
        VatModel vat,
        List<Document> openInvoices,
        List<Document> openBills,
        List<Document> doubtfulInvoices,
        List<String> customers,
        Diagnostics diagnostics) {

    /** A recurring series of invoices (a retainer) or bills (the accountant's monthly fee). */
    public record RecurringDocuments(DocumentType type, RecurringPattern pattern, int termDays) {}

    /**
     * How the history was split between components. Discretionary flows (owner loans, dividends) are
     * owner decisions and are not projected.
     */
    public record Diagnostics(
            int transactions,
            int settledByDocuments,
            int vatPayments,
            int discretionary,
            int recurring,
            int irregular) {}

    private static final RecurringDetector DETECTOR = new RecurringDetector();

    public static CashModel build(CompanySnapshot s, ForecastSettings settings) {
        List<BankTransaction> txs = s.transactions();
        Set<Integer> settled = PaymentMatcher.settledTransactions(txs, s.documents());

        List<BankTransaction> vatTxs = new ArrayList<>();
        Set<Integer> vatIndexes = new HashSet<>();
        Set<Integer> discretionary = new HashSet<>();
        Map<String, List<Occurrence>> groups = new LinkedHashMap<>();
        Map<String, Integer> groupSign = new LinkedHashMap<>();
        for (int i = 0; i < txs.size(); i++) {
            if (settled.contains(i)) {
                continue;
            }
            BankTransaction t = txs.get(i);
            String key = Counterparties.key(t.party());
            if (settings.vat().counterpartyPattern().matcher(key).find()) {
                vatTxs.add(t);
                vatIndexes.add(i);
                continue;
            }
            if (settings.discretionaryPattern().matcher(key).find()) {
                discretionary.add(i);
                continue;
            }
            int sign = t.amount().signum();
            String groupKey = sign + "|" + key;
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                    .add(new Occurrence(t.date(), t.amount(), t.party(), List.of(i)));
            groupSign.put(groupKey, sign);
        }
        List<RecurringDetector.Series> series = new ArrayList<>();
        groups.forEach((groupKey, occ) -> series.add(new RecurringDetector.Series(
                groupKey.substring(groupKey.indexOf('|') + 1), groupSign.get(groupKey), occ)));
        List<RecurringDetector.Detection> detections = DETECTOR.detect(series, s.asOf());
        Set<Integer> recurring = new HashSet<>();
        detections.forEach(d -> recurring.addAll(d.sources()));

        List<BankTransaction> residual = new ArrayList<>();
        for (int i = 0; i < txs.size(); i++) {
            BankTransaction t = txs.get(i);
            if (!settled.contains(i)
                    && !recurring.contains(i)
                    && !vatIndexes.contains(i)
                    && !discretionary.contains(i)) {
                residual.add(t);
            }
        }

        List<RecurringDocuments> documentPatterns = new ArrayList<>();
        Set<Integer> recurringDocs = new HashSet<>();
        for (DocumentType type : DocumentType.values()) {
            Map<String, List<Occurrence>> byParty = new LinkedHashMap<>();
            List<Document> docs = s.documents();
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                if (d.type() == type) {
                    byParty.computeIfAbsent(Counterparties.key(d.counterparty()), k -> new ArrayList<>())
                            .add(new Occurrence(d.issueDate(), d.amount(), d.counterparty(), List.of(i)));
                }
            }
            List<RecurringDetector.Series> docSeries = new ArrayList<>();
            byParty.forEach((k, occ) -> docSeries.add(new RecurringDetector.Series(k, type.cashSign(), occ)));
            for (RecurringDetector.Detection d : DETECTOR.detect(docSeries, s.asOf())) {
                recurringDocs.addAll(d.sources());
                List<Integer> terms = d.sources().stream()
                        .map(i -> (int) ChronoUnit.DAYS.between(
                                docs.get(i).issueDate(), docs.get(i).dueDate()))
                        .sorted()
                        .toList();
                documentPatterns.add(new RecurringDocuments(type, d.pattern(), terms.get(terms.size() / 2)));
            }
        }
        List<Document> adHocInvoices = new ArrayList<>();
        List<Document> adHocBills = new ArrayList<>();
        for (int i = 0; i < s.documents().size(); i++) {
            if (recurringDocs.contains(i)) {
                continue;
            }
            Document d = s.documents().get(i);
            (d.type() == DocumentType.INVOICE ? adHocInvoices : adHocBills).add(d);
        }

        List<Document> openInvoices = new ArrayList<>();
        List<Document> openBills = new ArrayList<>();
        List<Document> doubtful = new ArrayList<>();
        for (Document d : s.documents()) {
            if (d.paidDate() != null) {
                continue;
            }
            long overdue = ChronoUnit.DAYS.between(d.dueDate(), s.asOf());
            if (d.type() == DocumentType.INVOICE) {
                (overdue > settings.doubtfulAfterDays() ? doubtful : openInvoices).add(d);
            } else {
                openBills.add(d);
            }
        }

        return new CashModel(
                s,
                settings,
                detections.stream().map(RecurringDetector.Detection::pattern).toList(),
                documentPatterns,
                DelayModel.fit(s.documents(), DocumentType.INVOICE, s.asOf(), settings.delayPriorStrength()),
                DelayModel.fit(s.documents(), DocumentType.BILL, s.asOf(), settings.delayPriorStrength()),
                AdHocDocumentModel.fit(adHocInvoices, s.asOf(), settings.baselineWindowDays()),
                AdHocDocumentModel.fit(adHocBills, s.asOf(), settings.baselineWindowDays()),
                BaselineModel.fit(residual, s.historyStart(), s.asOf(), settings.baselineWindowDays()),
                VatModel.fit(vatTxs, s.documents(), s.historyStart(), s.asOf(), settings.vat()),
                openInvoices,
                openBills,
                doubtful,
                customers(s.documents(), detections),
                new Diagnostics(
                        txs.size(),
                        settled.size(),
                        vatTxs.size(),
                        discretionary.size(),
                        recurring.size(),
                        residual.size()));
    }

    /** Names a scenario can refer to: invoice counterparties and the payers of recurring income. */
    private static List<String> customers(List<Document> documents, List<RecurringDetector.Detection> detections) {
        java.util.TreeMap<String, String> byKey = new java.util.TreeMap<>();
        for (Document d : documents) {
            if (d.type() == DocumentType.INVOICE) {
                byKey.putIfAbsent(Counterparties.key(d.counterparty()), d.counterparty());
            }
        }
        for (RecurringDetector.Detection d : detections) {
            if (d.pattern().sign() > 0) {
                byKey.putIfAbsent(d.pattern().counterpartyKey(), d.pattern().label());
            }
        }
        return byKey.values().stream().sorted().toList();
    }

    /** True if {@code name} refers to one of {@link #customers()}. */
    public boolean isCustomer(String name) {
        String key = Counterparties.key(name);
        return customers.stream().anyMatch(c -> Counterparties.key(c).equals(key));
    }

    public DelayModel delays(DocumentType type) {
        return type == DocumentType.INVOICE ? invoiceDelays : billDelays;
    }

    public AdHocDocumentModel adHoc(DocumentType type) {
        return type == DocumentType.INVOICE ? adHocInvoices : adHocBills;
    }
}
