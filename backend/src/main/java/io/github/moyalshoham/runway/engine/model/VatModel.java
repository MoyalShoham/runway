package io.github.moyalshoham.runway.engine.model;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.BusinessCalendar;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * VAT payments, computed from documents rather than guessed from past payments.
 *
 * <p>The liability for a reporting period is the VAT on invoices issued in it minus the VAT on bills
 * issued in it, plus a residual: the median, over the last three periods, of what was actually paid
 * minus what the documents explain. The residual covers VAT that never appears on an imported
 * document, such as card sales (output VAT) or card purchases (input VAT).
 *
 * <p>The model is disabled when the history contains no VAT payment (e.g. an exempt dealer).
 */
public final class VatModel {

    static final int MATCH_BEFORE_DAYS = 10;
    static final int MATCH_AFTER_DAYS = 20;
    static final int RESIDUAL_PERIODS = 3;
    /** A payment not seen this many days after its nominal date is treated as already made elsewhere. */
    static final int LATE_GRACE_DAYS = 7;

    /** A VAT reporting period and the date its payment is due. */
    public record Period(LocalDate start, LocalDate end, LocalDate nominalPaymentDate) {}

    private final VatSettings settings;
    private final boolean enabled;
    private final BigDecimal residual;
    private final List<Integer> dateResiduals;
    private final Map<LocalDate, BigDecimal> knownDocumentVat;
    private final List<LocalDate> paymentDates;

    private VatModel(
            VatSettings settings,
            boolean enabled,
            BigDecimal residual,
            List<Integer> dateResiduals,
            Map<LocalDate, BigDecimal> knownDocumentVat,
            List<LocalDate> paymentDates) {
        this.settings = settings;
        this.enabled = enabled;
        this.residual = residual;
        this.dateResiduals = List.copyOf(dateResiduals);
        this.knownDocumentVat = Map.copyOf(knownDocumentVat);
        this.paymentDates = List.copyOf(paymentDates);
    }

    public static VatModel fit(
            List<BankTransaction> vatTransactions,
            List<Document> documents,
            LocalDate historyStart,
            LocalDate asOf,
            VatSettings settings) {
        Map<LocalDate, BigDecimal> docVat = new HashMap<>();
        VatModel shape = new VatModel(settings, true, Money.ZERO, List.of(), Map.of(), List.of());
        for (Document d : documents) {
            docVat.merge(shape.periodOf(d.issueDate()).start(), shape.signedVat(d.type(), d.amount()), BigDecimal::add);
        }
        if (vatTransactions.isEmpty()) {
            return new VatModel(settings, false, Money.ZERO, List.of(), docVat, List.of());
        }
        List<BigDecimal> residuals = new ArrayList<>();
        List<Integer> dateResiduals = new ArrayList<>();
        Period p = shape.periodOf(historyStart);
        // Only periods that start on or after the history start have complete document data.
        if (p.start().isBefore(historyStart)) {
            p = shape.next(p);
        }
        for (; !p.nominalPaymentDate().plusDays(MATCH_AFTER_DAYS).isAfter(asOf); p = shape.next(p)) {
            BigDecimal paid = Money.ZERO;
            BankTransaction largest = null;
            for (BankTransaction t : vatTransactions) {
                long offset = ChronoUnit.DAYS.between(p.nominalPaymentDate(), t.date());
                if (offset >= -MATCH_BEFORE_DAYS && offset <= MATCH_AFTER_DAYS) {
                    paid = paid.subtract(t.amount());
                    if (largest == null
                            || t.amount().abs().compareTo(largest.amount().abs()) > 0) {
                        largest = t;
                    }
                }
            }
            BigDecimal explained = docVat.getOrDefault(p.start(), Money.ZERO);
            residuals.add(paid.subtract(explained));
            if (largest != null) {
                dateResiduals.add((int) ChronoUnit.DAYS.between(p.nominalPaymentDate(), largest.date()));
            }
        }
        List<BigDecimal> recent = residuals.subList(Math.max(0, residuals.size() - RESIDUAL_PERIODS), residuals.size());
        BigDecimal residual = recent.isEmpty() ? Money.ZERO : Money.median(recent);
        List<LocalDate> paymentDates =
                vatTransactions.stream().map(BankTransaction::date).sorted().toList();
        return new VatModel(settings, true, residual, dateResiduals, docVat, paymentDates);
    }

    public boolean enabled() {
        return enabled;
    }

    public BigDecimal residual() {
        return residual;
    }

    /** VAT effect on the liability of a document: invoices add VAT owed, bills reduce it. */
    public BigDecimal signedVat(DocumentType type, BigDecimal gross) {
        BigDecimal vat = Money.vatPortion(gross, settings.rate());
        return type == DocumentType.INVOICE ? vat : vat.negate();
    }

    public Period periodOf(LocalDate date) {
        int months = settings.periodMonths();
        int startMonth = ((date.getMonthValue() - 1) / months) * months + 1;
        YearMonth start = YearMonth.of(date.getYear(), startMonth);
        YearMonth end = start.plusMonths(months - 1L);
        LocalDate due = BusinessCalendar.nextBusinessDay(end.plusMonths(1).atDay(settings.paymentDayOfMonth()));
        return new Period(start.atDay(1), end.atEndOfMonth(), due);
    }

    public Period next(Period p) {
        return periodOf(p.end().plusDays(1));
    }

    /**
     * Periods whose payment falls in (asOf, until]. A period whose nominal date passed within the
     * last {@link #LATE_GRACE_DAYS} days with no payment seen is included too: it is late, not skipped.
     */
    public List<Period> periodsPayable(LocalDate asOf, LocalDate until) {
        List<Period> periods = new ArrayList<>();
        if (!enabled) {
            return periods;
        }
        Period p = periodOf(asOf.minusMonths(settings.periodMonths() + 1L));
        for (; !p.nominalPaymentDate().isAfter(until); p = next(p)) {
            LocalDate nominal = p.nominalPaymentDate();
            if (nominal.isAfter(asOf)) {
                periods.add(p);
            } else if (!nominal.isBefore(asOf.minusDays(LATE_GRACE_DAYS)) && !paidAround(nominal, asOf)) {
                periods.add(p);
            }
        }
        return periods;
    }

    public BigDecimal knownDocumentVat(Period p) {
        return knownDocumentVat.getOrDefault(p.start(), Money.ZERO);
    }

    /** Payment day for a period: nominal date plus a resampled historical delay, never before asOf+1. */
    public LocalDate samplePaymentDate(Period p, LocalDate asOf, SplittableRandom rng) {
        int shift = dateResiduals.isEmpty() ? 0 : dateResiduals.get(rng.nextInt(dateResiduals.size()));
        LocalDate date = p.nominalPaymentDate().plusDays(shift);
        return date.isAfter(asOf) ? date : asOf.plusDays(1);
    }

    private boolean paidAround(LocalDate nominal, LocalDate asOf) {
        for (LocalDate d : paymentDates) {
            if (!d.isBefore(nominal.minusDays(MATCH_BEFORE_DAYS)) && !d.isAfter(asOf)) {
                return true;
            }
        }
        return false;
    }
}
