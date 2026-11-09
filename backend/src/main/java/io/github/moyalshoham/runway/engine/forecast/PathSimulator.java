package io.github.moyalshoham.runway.engine.forecast;

import io.github.moyalshoham.runway.engine.domain.BusinessCalendar;
import io.github.moyalshoham.runway.engine.domain.Counterparties;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.engine.domain.Money;
import io.github.moyalshoham.runway.engine.model.AdHocDocumentModel;
import io.github.moyalshoham.runway.engine.model.CashModel;
import io.github.moyalshoham.runway.engine.model.VatModel;
import io.github.moyalshoham.runway.engine.recurring.RecurringPattern;
import io.github.moyalshoham.runway.engine.scenario.Scenario;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/** Simulates one path of daily cash flows from a {@link CashModel}, with scenarios applied. */
final class PathSimulator {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final CashModel model;
    private final LocalDate asOf;
    private final LocalDate end;
    private final int horizon;
    private final long seed;
    private final Map<String, Integer> extraDelay = new HashMap<>();
    private final Map<String, LocalDate> lostFrom = new HashMap<>();
    private final List<Scenario.NewHire> hires = new ArrayList<>();
    private final List<Scenario.OneOff> oneOffs = new ArrayList<>();

    PathSimulator(CashModel model, List<Scenario> scenarios) {
        this.model = model;
        this.asOf = model.snapshot().asOf();
        this.horizon = model.settings().horizonDays();
        this.end = asOf.plusDays(horizon);
        this.seed = model.settings().seed();
        for (Scenario s : scenarios) {
            switch (s) {
                case Scenario.CustomerPaysLate late ->
                    extraDelay.merge(Counterparties.key(late.customer()), late.extraDays(), Integer::sum);
                case Scenario.LoseCustomer lose ->
                    lostFrom.merge(Counterparties.key(lose.customer()), lose.from(), (a, b) -> a.isBefore(b) ? a : b);
                case Scenario.NewHire hire -> hires.add(hire);
                case Scenario.OneOff oneOff -> oneOffs.add(oneOff);
            }
        }
    }

    void simulate(int path, Accumulator acc) {
        Map<LocalDate, BigDecimal> simulatedVat = new HashMap<>();
        openDocuments(path, acc, model.openInvoices(), Component.OPEN_INVOICES);
        openDocuments(path, acc, model.openBills(), Component.OPEN_BILLS);
        recurringTransactions(path, acc);
        recurringDocuments(path, acc, simulatedVat);
        adHocDocuments(path, acc, DocumentType.INVOICE, simulatedVat);
        adHocDocuments(path, acc, DocumentType.BILL, simulatedVat);
        irregular(path, acc);
        vat(path, acc, simulatedVat);
        scenarios(acc);
    }

    private void openDocuments(int path, Accumulator acc, List<Document> docs, Component component) {
        for (Document d : docs) {
            SplittableRandom rng = RandomStreams.of(seed, path, "open:" + d.type() + ":" + d.number());
            String party = Counterparties.key(d.counterparty());
            int overdue = (int) ChronoUnit.DAYS.between(d.dueDate(), asOf);
            int delay = model.delays(d.type()).sample(party, overdue, rng);
            LocalDate paid = d.dueDate().plusDays(delay + lateDays(d.type(), party));
            String label =
                    (d.type() == DocumentType.INVOICE ? "Invoice " : "Bill ") + d.number() + " · " + d.counterparty();
            acc.add(
                    component,
                    "open:" + d.type() + ":" + d.number(),
                    label,
                    BusinessCalendar.nextBusinessDay(paid),
                    d.signedAmount());
        }
    }

    private void recurringTransactions(int path, Accumulator acc) {
        for (RecurringPattern p : model.transactionPatterns()) {
            if (!p.active()) {
                continue;
            }
            SplittableRandom rng = RandomStreams.of(seed, path, "rec:" + p.sign() + ":" + p.key());
            for (LocalDate nominal :
                    p.nominalDatesAfterLastSeen(end.plusDays(p.cadence().toleranceDays()))) {
                LocalDate date = occurrenceDate(p, nominal, rng);
                if (date == null) {
                    continue;
                }
                LocalDate lost = lostFrom.get(p.counterpartyKey());
                if (p.sign() > 0 && lost != null && !date.isBefore(lost)) {
                    continue;
                }
                BigDecimal amount = sampleAmount(p, rng);
                acc.add(
                        Component.RECURRING,
                        "rec:" + p.sign() + ":" + p.key() + "@" + nominal,
                        p.label(),
                        date,
                        p.sign() > 0 ? amount : amount.negate());
            }
        }
    }

    private void recurringDocuments(int path, Accumulator acc, Map<LocalDate, BigDecimal> simulatedVat) {
        VatModel vat = model.vat();
        for (CashModel.RecurringDocuments rd : model.documentPatterns()) {
            RecurringPattern p = rd.pattern();
            if (!p.active()) {
                continue;
            }
            String party = p.counterpartyKey();
            SplittableRandom rng = RandomStreams.of(seed, path, "recdoc:" + rd.type() + ":" + p.key());
            for (LocalDate nominal : p.nominalDatesAfterLastSeen(end)) {
                LocalDate issued = occurrenceDate(p, nominal, rng);
                if (issued == null) {
                    continue;
                }
                LocalDate lost = lostFrom.get(party);
                if (rd.type() == DocumentType.INVOICE && lost != null && !issued.isBefore(lost)) {
                    continue;
                }
                BigDecimal amount = sampleAmount(p, rng);
                simulatedVat.merge(vat.periodOf(issued).start(), vat.signedVat(rd.type(), amount), BigDecimal::add);
                LocalDate paid = paymentDate(rd.type(), party, issued, rd.termDays(), rng);
                Component c = rd.type() == DocumentType.INVOICE ? Component.FUTURE_INVOICES : Component.FUTURE_BILLS;
                BigDecimal signed = rd.type() == DocumentType.INVOICE ? amount : amount.negate();
                acc.add(
                        c,
                        "recdoc:" + rd.type() + ":" + p.key() + "@" + nominal,
                        p.label() + " (recurring)",
                        paid,
                        signed);
            }
        }
    }

    private void adHocDocuments(int path, Accumulator acc, DocumentType type, Map<LocalDate, BigDecimal> simulatedVat) {
        AdHocDocumentModel adHoc = model.adHoc(type);
        SplittableRandom rng = RandomStreams.of(seed, path, "adhoc:" + type);
        Component c = type == DocumentType.INVOICE ? Component.FUTURE_INVOICES : Component.FUTURE_BILLS;
        String noun = type == DocumentType.INVOICE ? "New invoices" : "New bills";
        for (AdHocDocumentModel.NewDocument doc : adHoc.generate(asOf, horizon, rng)) {
            AdHocDocumentModel.Template t = doc.template();
            LocalDate lost = lostFrom.get(t.counterpartyKey());
            if (type == DocumentType.INVOICE && lost != null && !doc.issueDate().isBefore(lost)) {
                continue;
            }
            simulatedVat.merge(
                    model.vat().periodOf(doc.issueDate()).start(),
                    model.vat().signedVat(type, t.amount()),
                    BigDecimal::add);
            LocalDate paid = paymentDate(type, t.counterpartyKey(), doc.issueDate(), t.termDays(), rng);
            YearMonth month = YearMonth.from(paid);
            BigDecimal signed =
                    type == DocumentType.INVOICE ? t.amount() : t.amount().negate();
            acc.add(c, "adhoc:" + type + ":" + month, noun + " (projected), " + month.format(MONTH), paid, signed);
        }
    }

    private void irregular(int path, Accumulator acc) {
        SplittableRandom rng = RandomStreams.of(seed, path, "irregular");
        BigDecimal[] flows = model.baseline().sample(asOf, horizon, rng);
        for (int h = 1; h <= horizon; h++) {
            BigDecimal f = flows[h - 1];
            if (f.signum() != 0) {
                LocalDate day = asOf.plusDays(h);
                YearMonth month = YearMonth.from(day);
                acc.add(Component.IRREGULAR, "irregular:" + month, "Irregular flows, " + month.format(MONTH), day, f);
            }
        }
    }

    private void vat(int path, Accumulator acc, Map<LocalDate, BigDecimal> simulatedVat) {
        VatModel vat = model.vat();
        for (VatModel.Period p : vat.periodsPayable(asOf, end)) {
            BigDecimal liability = vat.knownDocumentVat(p)
                    .add(simulatedVat.getOrDefault(p.start(), Money.ZERO))
                    .add(vat.residual());
            if (liability.signum() <= 0) {
                continue;
            }
            SplittableRandom rng = RandomStreams.of(seed, path, "vat:" + p.start());
            LocalDate date = vat.samplePaymentDate(p, asOf, rng);
            String label = "VAT " + YearMonth.from(p.start()).format(MONTH) + " – "
                    + YearMonth.from(p.end()).format(MONTH);
            acc.add(Component.VAT, "vat:" + p.start(), label, date, liability.negate());
        }
    }

    private void scenarios(Accumulator acc) {
        for (Scenario.NewHire hire : hires) {
            for (YearMonth m = YearMonth.from(hire.startDate()); ; m = m.plusMonths(1)) {
                LocalDate payDate =
                        BusinessCalendar.nextBusinessDay(m.plusMonths(1).atDay(hire.payDay()));
                if (payDate.isAfter(end)) {
                    break;
                }
                BigDecimal cost = hire.monthlyCost();
                if (m.equals(YearMonth.from(hire.startDate()))) {
                    int worked = m.lengthOfMonth() - hire.startDate().getDayOfMonth() + 1;
                    cost = Money.times(cost, worked / (double) m.lengthOfMonth());
                }
                acc.add(
                        Component.SCENARIO,
                        "hire:" + hire.label() + ":" + m,
                        hire.label() + " salary, " + m.format(MONTH),
                        payDate,
                        cost.negate());
            }
        }
        for (Scenario.OneOff o : oneOffs) {
            acc.add(Component.SCENARIO, "oneoff:" + o.label() + ":" + o.date(), o.label(), o.date(), o.amount());
        }
    }

    /**
     * Actual date of an occurrence whose nominal date is {@code nominal}: nominal plus a resampled
     * historical deviation. An occurrence whose nominal date already passed is still expected while
     * within the cadence's tolerance (it is late), and dropped after that (it was skipped).
     */
    private LocalDate occurrenceDate(RecurringPattern p, LocalDate nominal, SplittableRandom rng) {
        List<Integer> residuals = p.dateResiduals();
        if (!nominal.isAfter(asOf)) {
            if (ChronoUnit.DAYS.between(nominal, asOf) > p.cadence().toleranceDays()) {
                return null;
            }
            List<Integer> later = residuals.stream()
                    .filter(r -> nominal.plusDays(r).isAfter(asOf))
                    .toList();
            return later.isEmpty() ? asOf.plusDays(1) : nominal.plusDays(later.get(rng.nextInt(later.size())));
        }
        LocalDate date = nominal.plusDays(residuals.get(rng.nextInt(residuals.size())));
        return date.isAfter(asOf) ? date : asOf.plusDays(1);
    }

    private static BigDecimal sampleAmount(RecurringPattern p, SplittableRandom rng) {
        List<Double> deviations = p.relativeAmountDeviations();
        double deviation = deviations.get(rng.nextInt(deviations.size()));
        return Money.times(p.amount(), Math.max(0.0, 1.0 + deviation));
    }

    /** Payment date of a document issued in the future: issue + terms + sampled delay, never before issue. */
    private LocalDate paymentDate(
            DocumentType type, String party, LocalDate issued, int termDays, SplittableRandom rng) {
        LocalDate due = issued.plusDays(termDays);
        int overdue = (int) ChronoUnit.DAYS.between(due, asOf);
        int delay = model.delays(type).sample(party, overdue, rng);
        LocalDate paid = due.plusDays(delay + lateDays(type, party));
        if (paid.isBefore(issued)) {
            paid = issued;
        }
        return BusinessCalendar.nextBusinessDay(paid);
    }

    private int lateDays(DocumentType type, String party) {
        return type == DocumentType.INVOICE ? extraDelay.getOrDefault(party, 0) : 0;
    }
}
