package io.github.moyalshoham.runway.sim;

import io.github.moyalshoham.runway.engine.domain.BankTransaction;
import io.github.moyalshoham.runway.engine.domain.BusinessCalendar;
import io.github.moyalshoham.runway.engine.domain.Document;
import io.github.moyalshoham.runway.engine.domain.DocumentType;
import io.github.moyalshoham.runway.engine.domain.Money;
import io.github.moyalshoham.runway.engine.model.VatSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Generates a synthetic Israeli small business (ILS; VAT at the rate in force on each date, 17% until
 * the end of 2024 and 18% from 2025) with a full bank history and its
 * invoices and bills.
 *
 * <p>The generator is deliberately richer than the forecaster's model, so the backtest is not a
 * closed loop: customers have hidden payment-behaviour types and occasionally never pay, payroll
 * changes with hires, leavers and January raises, utilities are seasonal, VAT refunds and income-tax
 * advances exist, and the owner injects a loan when the overdraft gets deep or takes a dividend
 * when cash piles up. None of those reactions are visible to the forecaster in advance.
 */
public final class CompanyGenerator {

    public static final LocalDate EVAL_START = LocalDate.of(2023, 1, 1);
    private static final VatSettings ISRAEL_VAT = VatSettings.israel();
    /** Counterparty of owner loans, injected when the overdraft gets deep (a reaction to a shortfall). */
    public static final String OWNER_LOAN = "הלוואת בעלים";
    /** Counterparty of dividends, taken when cash piles up. */
    public static final String OWNER_DIVIDEND = "דיבידנד לבעלים";

    public SyntheticCompany generate(long seed) {
        return generate(seed, EVAL_START, 3);
    }

    /** {@code years} years of history starting on {@code start}. */
    public SyntheticCompany generate(long seed, LocalDate start, int years) {
        return new Run(seed, start, start.plusYears(years).minusDays(1)).build();
    }

    private enum Terms {
        NET_0(0, false),
        NET_30(30, false),
        EOM_30(30, true),
        EOM_60(60, true),
        EOM_90(90, true);

        final int days;
        final boolean endOfMonth;

        Terms(int days, boolean endOfMonth) {
            this.days = days;
            this.endOfMonth = endOfMonth;
        }

        LocalDate due(LocalDate issued) {
            return (endOfMonth ? YearMonth.from(issued).atEndOfMonth() : issued).plusDays(days);
        }
    }

    /** Hidden payment behaviour of a customer: mean and spread of days late. */
    private record Payer(double meanDelay, double sdDelay, double defaultRate) {}

    /**
     * @param withholding share of the net amount the customer withholds at source and remits to the
     *     tax authority (ניכוי במקור); the bank receives the invoice total minus that
     */
    private record Customer(String name, Terms terms, Payer payer, double weight, double withholding) {}

    private record Employee(String name, double gross, double netRatio, LocalDate from, LocalDate to) {}

    private record Retainer(Customer customer, double monthlyNet, YearMonth from, YearMonth to) {}

    private static final String[] CUSTOMER_NAMES = {
        "Shefa Logistics",
        "Galil Foods",
        "Tavor Systems",
        "Negev Solar",
        "אורן הנדסה",
        "Kinneret Media",
        "Carmel Health",
        "Arava Labs",
        "Yarden Retail",
        "מעיין השקעות",
        "Hermon Insurance Agency",
        "Ofek Cyber",
        "גלבוע תעשיות",
        "Sharon Clinics",
        "Modiin Municipality",
        "Eilat Hotels Group",
        "Yam Shipping",
        "בית ספר רעות",
        "Golan Wineries",
        "Jezreel Agritech"
    };
    private static final String[] EMPLOYEE_NAMES = {
        "Dana Levi",
        "Yossi Cohen",
        "Noa Mizrahi",
        "Avi Peretz",
        "Maya Biton",
        "Omer Friedman",
        "Tamar Azoulay",
        "Eitan Shapiro",
        "Shira Dahan",
        "Lior Avraham",
        "Yael Katz",
        "Ido Ben-David",
        "Michal Ohayon",
        "Ron Malka",
        "Adi Segal",
        "Nir Amar",
        "Hila Gabay",
        "Tal Rosen",
        "Gal Hadad",
        "Roni Elbaz"
    };
    private static final String[] MERCHANTS = {
        "שופרסל", "פז דלק", "Office Depot", "KSP", "Ivory", "Wolt", "סונול", "רמי לוי", "Aroma", "AliExpress",
        "Amazon", "Home Center", "ACE", "Super-Pharm", "Delek", "Golda", "Cafe Cafe", "Bug", "Max Stock", "Yango"
    };
    private static final String[][] SUBSCRIPTIONS = {
        // name, monthly ILS, foreign (no VAT, FX noise)
        {"Google Workspace", "180", "1"},
        {"Microsoft 365", "240", "1"},
        {"Monday.com", "450", "1"},
        {"Adobe", "320", "1"},
        {"Slack", "160", "1"},
        {"Zoom", "75", "1"},
        {"Bezeq Business", "390", "0"},
        {"Cellcom", "620", "0"},
        {"Partner", "540", "0"},
        {"Hot Business Internet", "280", "0"},
        {"Priority ERP", "900", "0"},
        {"Wix", "120", "1"}
    };

    private static final class Run {
        final long seed;
        final SplittableRandom rng;
        final LocalDate start;
        final LocalDate end;
        final Archetype archetype;
        final List<BankTransaction> txs = new ArrayList<>();
        final List<Document> docs = new ArrayList<>();
        /** Output minus input VAT by bimonthly period start. */
        final Map<LocalDate, BigDecimal> vatByPeriod = new HashMap<>();
        /** Net revenue by bimonthly period start, for income-tax advances. */
        final Map<LocalDate, BigDecimal> revenueByPeriod = new HashMap<>();

        double growth;
        double revenue0;

        Run(long seed, LocalDate start, LocalDate end) {
            this.seed = seed;
            this.rng = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 17);
            this.start = start;
            this.end = end;
            this.archetype = Archetype.values()[Math.floorMod(seed, Archetype.values().length)];
        }

        SyntheticCompany build() {
            double margin = uniform(-0.08, 0.12);
            growth = uniform(-0.10, 0.25);
            double fixed = 0;
            fixed += payroll();
            fixed += premises();
            fixed += subscriptionsAndServices();
            double ownerDraw = round100(uniform(6000, 18000));
            fixed += ownerDraw;
            monthly("משיכת בעלים", "Owner draw", ownerDraw, 10, start, end, false);
            double variableShare =
                    switch (archetype) {
                        case CONSULTANCY -> 0.05;
                        case AGENCY -> 0.25;
                        case RETAIL -> 0.38;
                        case CONTRACTOR -> 0.42;
                    };
            double cardPerDay = cardPurchasesPerBusinessDay();
            // Expected irregular spending: card purchases (lognormal, median 180, sigma 1), two
            // equipment purchases a year and holiday gifts twice a year.
            double irregular = cardPerDay * 23.5 * 180 * Math.exp(0.5) + 2 * 12000 * Math.exp(0.18) / 12 + 8000.0 / 12;
            double advanceRate = uniform(0.02, 0.06);
            // Costs drift up with January raises (4%/year); calibrate on the middle of the history.
            double costs = (fixed + irregular) * Math.pow(1.04, 1.5);
            revenue0 = costs / (1 - variableShare - margin - advanceRate);
            revenue(variableShare);
            irregularSpending(cardPerDay);
            vatAndAdvances(advanceRate);
            BigDecimal minimum = BigDecimal.valueOf(Math.max(5000, Math.round(0.5 * fixed / 5000) * 5000))
                    .setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal opening = money(fixed * uniform(0.4, 1.6));
            ownerReactions(opening, fixed);
            List<BankTransaction> bank = txs.stream()
                    .filter(t -> !t.date().isBefore(start) && !t.date().isAfter(end))
                    .sorted(Comparator.comparing(BankTransaction::date))
                    .toList();
            List<Document> known = numberChronologically(docs).stream()
                    .filter(d -> !d.issueDate().isAfter(end))
                    .map(d -> d.asKnownOn(end))
                    .toList();
            String name = companyName();
            return new SyntheticCompany(seed, name, archetype, start.minusDays(1), opening, minimum, end, bank, known);
        }

        // ---- costs -------------------------------------------------------------------------

        /** Employees, payroll transfers, pension fund and withholding; returns monthly employer cost. */
        double payroll() {
            int count =
                    switch (archetype) {
                        case CONSULTANCY -> rng.nextInt(2, 6);
                        case AGENCY, RETAIL -> rng.nextInt(4, 10);
                        case CONTRACTOR -> rng.nextInt(3, 7);
                    };
            double[] grossRange =
                    switch (archetype) {
                        case CONSULTANCY -> new double[] {18000, 34000};
                        case AGENCY -> new double[] {12000, 28000};
                        case RETAIL -> new double[] {7000, 12000};
                        case CONTRACTOR -> new double[] {10000, 20000};
                    };
            int payDay = new int[] {1, 5, 9, 9, 10}[rng.nextInt(5)];
            String fund = rng.nextBoolean() ? "מגדל מקפת" : "הראל פנסיה";
            List<Employee> staff = new ArrayList<>();
            int nameIndex = rng.nextInt(EMPLOYEE_NAMES.length);
            double monthlyCost = 0;
            for (int i = 0; i < count; i++) {
                boolean firstInPosition = true;
                LocalDate from = start.minusYears(1);
                // A quarter of positions turn over once during the history, with a 1-2 month gap.
                while (true) {
                    LocalDate to = rng.nextDouble() < 0.25
                            ? start.plusDays(rng.nextInt(60, (int) ChronoUnit.DAYS.between(start, end)))
                            : end.plusYears(1);
                    String name = EMPLOYEE_NAMES[nameIndex++ % EMPLOYEE_NAMES.length];
                    double gross = uniform(grossRange[0], grossRange[1]);
                    staff.add(new Employee(name, gross, uniform(0.72, 0.8), from, to));
                    if (firstInPosition) {
                        monthlyCost += gross * 1.2;
                        firstInPosition = false;
                    }
                    if (!to.isBefore(end)) {
                        break;
                    }
                    from = to.plusDays(rng.nextInt(30, 70));
                    if (from.isAfter(end)) {
                        break;
                    }
                }
            }
            boolean hourly = archetype == Archetype.RETAIL;
            for (YearMonth m = YearMonth.from(start.minusMonths(1));
                    !m.isAfter(YearMonth.from(end));
                    m = m.plusMonths(1)) {
                double raise = Math.pow(1.04, m.getYear() - start.getYear());
                double totalGross = 0;
                LocalDate pay = BusinessCalendar.nextBusinessDay(m.plusMonths(1).atDay(payDay));
                for (Employee e : staff) {
                    if (e.from().isAfter(m.atEndOfMonth()) || e.to().isBefore(m.atDay(1))) {
                        continue;
                    }
                    double worked = workedShare(e, m);
                    double gross = e.gross() * raise * worked * (hourly ? lognormal(1.0, 0.1) : 1.0);
                    if (m.getMonth() == Month.DECEMBER && archetype == Archetype.CONSULTANCY) {
                        gross *= 1.5; // year-end bonus
                    }
                    totalGross += gross;
                    add(pay, -gross * e.netRatio(), e.name(), "משכורת " + e.name(), 0);
                }
                if (totalGross > 0) {
                    LocalDate fifteenth =
                            BusinessCalendar.nextBusinessDay(m.plusMonths(1).atDay(15));
                    add(fifteenth, -totalGross * 0.205, fund, "הפקדה לקרן פנסיה " + fund, 0);
                    add(fifteenth, -totalGross * 0.126, "ביטוח לאומי", "ביטוח לאומי - ניכויים", 0);
                    add(fifteenth, -totalGross * 0.095, "מס הכנסה ניכויים", "מס הכנסה - ניכויים במקור", 0);
                }
            }
            return monthlyCost;
        }

        private double workedShare(Employee e, YearMonth m) {
            LocalDate from = e.from().isAfter(m.atDay(1)) ? e.from() : m.atDay(1);
            LocalDate to = e.to().isBefore(m.atEndOfMonth()) ? e.to() : m.atEndOfMonth();
            return (ChronoUnit.DAYS.between(from, to) + 1) / (double) m.lengthOfMonth();
        }

        /** Rent, arnona, electricity, water; returns their monthly average. */
        double premises() {
            double rent =
                    switch (archetype) {
                        case CONSULTANCY -> uniform(5000, 12000);
                        case AGENCY -> uniform(9000, 22000);
                        case RETAIL -> uniform(14000, 32000);
                        case CONTRACTOR -> uniform(4000, 9000);
                    };
            rent = round100(rent);
            int rentDay = rng.nextBoolean() ? 1 : 5;
            Month anniversary = Month.of(rng.nextInt(1, 13));
            double increase = uniform(0.02, 0.045);
            double current = rent;
            String landlord = rng.nextBoolean() ? "נכסי אלון בעמ" : "Azrieli Properties";
            for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
                if (m.getMonth() == anniversary && m.isAfter(YearMonth.from(start))) {
                    current = round100(current * (1 + increase));
                }
                addWithVat(day(m, rentDay), -current, landlord, "הוראת קבע שכירות");
            }
            double arnona = round100(rent * uniform(0.25, 0.45));
            double electricity = rent * (archetype == Archetype.RETAIL ? uniform(0.25, 0.4) : uniform(0.06, 0.12));
            int iecDay = rng.nextInt(16, 25);
            for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
                double inflation = Math.pow(1.025, m.getYear() - start.getYear());
                if (m.getMonthValue() % 2 == 1) {
                    add(day(m, 1), -arnona * inflation, "עיריית תל אביב-יפו", "ארנונה דו-חודשית", 0);
                } else {
                    double season =
                            switch (m.getMonth()) {
                                case JULY, AUGUST -> 1.5;
                                case JUNE, SEPTEMBER, FEBRUARY -> 1.2;
                                case DECEMBER, JANUARY -> 1.3;
                                default -> 1.0;
                            };
                    double bill = 2 * electricity * season * lognormal(1.0, 0.08);
                    addWithVat(day(m, iecDay), -bill, "חברת החשמל", "חברת החשמל לישראל - חשבון תקופתי");
                    if (archetype == Archetype.RETAIL) {
                        double water = 2 * rent * 0.02 * lognormal(1.0, 0.1);
                        addWithVat(day(m, iecDay + 3), -water, "מי אביבים", "מים וביוב");
                    }
                }
            }
            return rent * (1 + increase / 2) + arnona / 2 + electricity * 1.2;
        }

        /** Subscriptions, insurance, loans, leasing, bank fees and the accountant; returns monthly total. */
        double subscriptionsAndServices() {
            double total = 0;
            int n = rng.nextInt(3, 9);
            List<Integer> picks = new ArrayList<>();
            while (picks.size() < n) {
                int i = rng.nextInt(SUBSCRIPTIONS.length);
                if (!picks.contains(i)) {
                    picks.add(i);
                }
            }
            for (int i : picks) {
                String[] s = SUBSCRIPTIONS[i];
                boolean foreign = s[2].equals("1");
                double amount = Double.parseDouble(s[1]) * uniform(0.6, 1.6);
                int day = rng.nextInt(1, 29);
                LocalDate cancel = rng.nextDouble() < 0.2 ? start.plusDays(rng.nextInt(90, 900)) : end.plusDays(1);
                LocalDate change = start.plusDays(rng.nextInt(60, 1000));
                double changed = amount * uniform(1.05, 1.3);
                for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
                    LocalDate d = day(m, day);
                    if (!d.isBefore(cancel)) {
                        break;
                    }
                    double a = d.isBefore(change) ? amount : changed;
                    if (foreign) {
                        a *= lognormal(1.0, 0.025); // USD billing, ILS exchange rate moves
                    }
                    add(d, -a, s[0], s[0] + (foreign ? " (חיוב במט\"ח)" : ""), foreign ? 0 : -vatOf(a, d));
                }
                total += amount;
            }
            if (rng.nextBoolean()) {
                double ins = round100(uniform(300, 1500));
                monthly("הראל ביטוח", "ביטוח עסק", ins, rng.nextInt(1, 28), start, end, false);
                total += ins;
            } else {
                double ins = round100(uniform(3000, 15000));
                int month = rng.nextInt(1, 13);
                for (int y = start.getYear(); y <= end.getYear(); y++) {
                    add(
                            BusinessCalendar.nextBusinessDay(LocalDate.of(y, month, 10)),
                            -ins * Math.pow(1.04, y - start.getYear()),
                            "מנורה ביטוח",
                            "ביטוח עסק שנתי",
                            0);
                }
                total += ins / 12;
            }
            if (rng.nextBoolean()) {
                double loan = round100(uniform(2000, 8000));
                LocalDate loanEnd = start.plusDays(rng.nextInt(200, 1400));
                monthly("בנק לאומי הלוואה", "החזר הלוואה", loan, 10, start, loanEnd, false);
                total += loan;
            }
            if (archetype == Archetype.CONSULTANCY || archetype == Archetype.CONTRACTOR) {
                double lease = round100(uniform(2500, 4200) * rng.nextInt(1, 4));
                monthly("אלבר ליסינג", "ליסינג רכב", lease, 2, start, end, true);
                total += lease;
            }
            for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
                LocalDate last = m.atEndOfMonth();
                while (!BusinessCalendar.isBusinessDay(last)) {
                    last = last.minusDays(1);
                }
                add(last, -uniform(40, 260), "עמלות בנק", "עמלת ניהול חשבון", 0);
            }
            double accountant = round100(uniform(1500, 4000));
            for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
                LocalDate issued = BusinessCalendar.nextBusinessDay(m.atDay(1));
                bill("רו\"ח כהן ושות'", issued, issued.plusDays(14), accountant * (1 + vatRate(issued)), 0);
            }
            total += accountant * 1.18;
            return total;
        }

        // ---- revenue -----------------------------------------------------------------------

        void revenue(double variableShare) {
            List<Customer> customers = customers();
            double retainerShare =
                    switch (archetype) {
                        case CONSULTANCY -> 0.5;
                        case AGENCY -> 0.2;
                        case RETAIL, CONTRACTOR -> 0;
                    };
            double salesShare = archetype == Archetype.RETAIL ? 0.92 : 0;
            double projectShare = 1 - retainerShare - salesShare;
            if (retainerShare > 0) {
                retainers(customers, retainerShare);
            }
            if (salesShare > 0) {
                cardSales(salesShare, variableShare);
            }
            projects(customers, projectShare, archetype == Archetype.RETAIL ? 0 : variableShare);
        }

        List<Customer> customers() {
            int n =
                    switch (archetype) {
                        case CONSULTANCY -> rng.nextInt(6, 13);
                        case AGENCY -> rng.nextInt(8, 17);
                        case RETAIL -> rng.nextInt(3, 7);
                        case CONTRACTOR -> rng.nextInt(5, 11);
                    };
            List<Customer> list = new ArrayList<>();
            int offset = rng.nextInt(CUSTOMER_NAMES.length);
            for (int i = 0; i < n; i++) {
                Terms terms = pickTerms();
                double r = rng.nextDouble();
                Payer payer = r < 0.1
                        ? new Payer(-3, 2, 0)
                        : r < 0.45
                                ? new Payer(1, 3, 0)
                                : r < 0.75
                                        ? new Payer(10, 7, 0.005)
                                        : r < 0.92 ? new Payer(30, 15, 0.015) : new Payer(55, 25, 0.03);
                // About a third of business customers withhold tax at source on payments for services.
                double withholding = archetype != Archetype.RETAIL && rng.nextDouble() < 0.35
                        ? new double[] {0.02, 0.05, 0.2}[rng.nextInt(3)]
                        : 0.0;
                list.add(new Customer(
                        CUSTOMER_NAMES[(offset + i) % CUSTOMER_NAMES.length],
                        terms,
                        payer,
                        1.0 / (i + 1),
                        withholding));
            }
            return list;
        }

        Terms pickTerms() {
            double r = rng.nextDouble();
            return switch (archetype) {
                case CONSULTANCY -> r < 0.3 ? Terms.NET_30 : r < 0.7 ? Terms.EOM_30 : Terms.EOM_60;
                case AGENCY -> r < 0.3 ? Terms.EOM_30 : r < 0.7 ? Terms.EOM_60 : Terms.EOM_90;
                case RETAIL -> r < 0.3 ? Terms.NET_0 : r < 0.7 ? Terms.NET_30 : Terms.EOM_30;
                case CONTRACTOR -> r < 0.2 ? Terms.EOM_30 : r < 0.7 ? Terms.EOM_60 : Terms.EOM_90;
            };
        }

        void retainers(List<Customer> customers, double share) {
            int n = Math.min(
                    customers.size(), archetype == Archetype.CONSULTANCY ? rng.nextInt(2, 5) : rng.nextInt(1, 3));
            double[] weights = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                weights[i] = uniform(0.6, 1.4);
                sum += weights[i];
            }
            for (int i = 0; i < n; i++) {
                Customer c = customers.get(i);
                YearMonth from = rng.nextDouble() < 0.7
                        ? YearMonth.from(start)
                        : YearMonth.from(start.plusDays(rng.nextInt(60, 700)));
                YearMonth to = rng.nextDouble() < 0.3
                        ? from.plusMonths(rng.nextInt(6, 30))
                        : YearMonth.from(end).plusMonths(6);
                Retainer r = new Retainer(c, revenue0 * share * weights[i] / sum, from, to);
                double amount = round100(r.monthlyNet());
                for (YearMonth m = r.from();
                        !m.isAfter(r.to()) && !m.isAfter(YearMonth.from(end));
                        m = m.plusMonths(1)) {
                    if (m.getMonth() == Month.JANUARY && m.isAfter(r.from())) {
                        amount = round100(amount * uniform(1.02, 1.06));
                    }
                    invoice(c, BusinessCalendar.nextBusinessDay(m.atDay(1)), amount);
                }
            }
        }

        void projects(List<Customer> customers, double share, double variableShare) {
            if (share <= 0) {
                return;
            }
            double median =
                    switch (archetype) {
                        case CONSULTANCY -> 22000;
                        case AGENCY -> 30000;
                        case RETAIL -> 4000;
                        case CONTRACTOR -> 70000;
                    };
            double sigma = 0.6;
            double mean = median * Math.exp(sigma * sigma / 2);
            double totalWeight =
                    customers.stream().mapToDouble(Customer::weight).sum();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (!BusinessCalendar.isBusinessDay(d)) {
                    continue;
                }
                double perBusinessDay = revenue0 * share / mean / 21.7;
                int n = poisson(perBusinessDay * seasonal(d) * trend(d));
                for (int i = 0; i < n; i++) {
                    Customer c = pickCustomer(customers, totalWeight);
                    double amount = round100(lognormal(median, sigma));
                    if (archetype == Archetype.CONTRACTOR) {
                        // Milestones: 30% on signing, 40% after ~45 days, 30% on completion.
                        invoice(c, d, round100(amount * 0.3));
                        invoice(
                                c,
                                BusinessCalendar.nextBusinessDay(d.plusDays(40 + rng.nextInt(15))),
                                round100(amount * 0.4));
                        invoice(
                                c,
                                BusinessCalendar.nextBusinessDay(d.plusDays(80 + rng.nextInt(30))),
                                round100(amount * 0.3));
                    } else {
                        invoice(c, d, amount);
                    }
                    if (variableShare > 0) {
                        supplierBills(d, amount * variableShare * lognormal(1.0, 0.2));
                    }
                }
            }
        }

        void supplierBills(LocalDate projectStart, double net) {
            String[] vendors =
                    switch (archetype) {
                        case AGENCY ->
                            new String[] {
                                "Studio Noa Freelance", "Gil Video Productions", "Lior Copywriting", "Pixel Lab"
                            };
                        case CONTRACTOR -> new String[] {"אבן ומלט בעמ", "ACE Building", "חשמל ישיר", "Ronen Plumbing"};
                        default -> new String[] {"AWS", "Travel Desk"};
                    };
            int parts = archetype == Archetype.CONTRACTOR ? 2 : 1;
            for (int i = 0; i < parts; i++) {
                String vendor = vendors[rng.nextInt(vendors.length)];
                LocalDate issued =
                        BusinessCalendar.nextBusinessDay(projectStart.plusDays(rng.nextInt(0, 25) + 30L * i));
                Terms terms = rng.nextBoolean() ? Terms.NET_30 : Terms.EOM_30;
                bill(vendor, issued, terms.due(issued), net / parts * (1 + vatRate(issued)), rng.nextInt(-2, 4));
            }
        }

        void cardSales(double share, double cogsShare) {
            String[] acquirers = {"ישראכרט", "כאל", "מקס"};
            double[] acquirerShare = {0.45, 0.35, 0.2};
            double weekTotal = 0;
            for (DayOfWeek dow : DayOfWeek.values()) {
                weekTotal += weekdayFactor(dow);
            }
            String[] suppliers = {"תנובה", "שטראוס", "קפה לנדוור", "Pri-Hagalil", "אסם"};
            int supplierCount = rng.nextInt(3, 6);
            Map<Integer, Double> weekSales = new HashMap<>();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                double w = weekdayFactor(d.getDayOfWeek());
                if (w == 0) {
                    continue;
                }
                double gross = revenue0
                        * share
                        * (1 + vatRate(d))
                        * 12
                        / 52
                        * w
                        / weekTotal
                        * seasonal(d)
                        * trend(d)
                        * lognormal(1.0, 0.18);
                addVat(d, vatOf(gross, d));
                addRevenue(d, gross / (1 + vatRate(d)));
                LocalDate settle = BusinessCalendar.nextBusinessDay(d.plusDays(2));
                for (int a = 0; a < acquirers.length; a++) {
                    add(
                            settle,
                            gross * 0.985 * acquirerShare[a] * lognormal(1.0, 0.05),
                            acquirers[a],
                            "זיכוי " + acquirers[a],
                            0,
                            false);
                }
                weekSales.merge((int) (ChronoUnit.DAYS.between(start, d) / 7), gross / (1 + vatRate(d)), Double::sum);
            }
            weekSales.forEach((week, net) -> {
                LocalDate weekEnd = start.plusDays(week * 7L + 6);
                for (int s = 0; s < supplierCount; s++) {
                    LocalDate issued = BusinessCalendar.nextBusinessDay(weekEnd.plusDays(s));
                    if (issued.isAfter(end)) {
                        continue;
                    }
                    double billNet = net * cogsShare / supplierCount * lognormal(1.0, 0.12);
                    Terms terms = s % 2 == 0 ? Terms.EOM_30 : Terms.NET_30;
                    bill(suppliers[s], issued, terms.due(issued), billNet * (1 + vatRate(issued)), rng.nextInt(-1, 3));
                }
            });
        }

        /** Israeli café week: closed on Shabbat, busiest on Thursday evening and Friday morning. */
        static double weekdayFactor(DayOfWeek d) {
            return switch (d) {
                case SUNDAY -> 0.85;
                case MONDAY -> 0.9;
                case TUESDAY -> 0.95;
                case WEDNESDAY -> 1.0;
                case THURSDAY -> 1.25;
                case FRIDAY -> 1.3;
                case SATURDAY -> 0.0;
            };
        }

        // ---- irregular, VAT, owner -----------------------------------------------------------

        double cardPurchasesPerBusinessDay() {
            return switch (archetype) {
                case CONSULTANCY -> 0.6;
                case AGENCY -> 1.0;
                case RETAIL, CONTRACTOR -> 1.5;
            };
        }

        void irregularSpending(double perDay) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                int n = poisson(BusinessCalendar.isBusinessDay(d) ? perDay : perDay * 0.2);
                for (int i = 0; i < n; i++) {
                    double amount = Math.min(8000, lognormal(180, 1.0));
                    String merchant = MERCHANTS[rng.nextInt(MERCHANTS.length)];
                    add(
                            d,
                            -amount,
                            merchant,
                            "כרטיס אשראי " + merchant,
                            rng.nextDouble() < 0.75 ? -vatOf(amount, d) : 0);
                }
                if (rng.nextDouble() < 2.0 / 365) {
                    double equipment = lognormal(12000, 0.6);
                    addWithVat(d, -equipment, "KSP", "רכישת ציוד");
                }
            }
            for (int y = start.getYear(); y <= end.getYear(); y++) {
                for (LocalDate gift : HebrewHolidays.giftDates(y)) {
                    add(BusinessCalendar.nextBusinessDay(gift), -uniform(2000, 6000), "BuyMe", "שי לחג לעובדים", 0);
                }
            }
        }

        void vatAndAdvances(double advanceRate) {
            for (YearMonth p = YearMonth.of(start.getYear(), 1); !p.atDay(1).isAfter(end); p = p.plusMonths(2)) {
                LocalDate periodStart = p.atDay(1);
                LocalDate due = BusinessCalendar.nextBusinessDay(p.plusMonths(2).atDay(15));
                BigDecimal liability = vatByPeriod.getOrDefault(periodStart, Money.ZERO);
                LocalDate paid = due.plusDays(rng.nextDouble() < 0.15 ? rng.nextInt(1, 4) : 0);
                if (liability.signum() > 0) {
                    BigDecimal whole =
                            liability.setScale(0, RoundingMode.HALF_EVEN).setScale(2, RoundingMode.HALF_EVEN);
                    add(BusinessCalendar.nextBusinessDay(paid), whole.negate(), "מע\"מ", "מע\"מ - תשלום דו-חודשי");
                } else if (liability.signum() < 0) {
                    LocalDate refund = BusinessCalendar.nextBusinessDay(due.plusDays(rng.nextInt(30, 60)));
                    add(refund, liability.negate(), "מע\"מ", "החזר מע\"מ");
                }
                BigDecimal revenue = revenueByPeriod.getOrDefault(periodStart, Money.ZERO);
                if (revenue.signum() > 0) {
                    add(
                            BusinessCalendar.nextBusinessDay(paid),
                            Money.times(revenue, -advanceRate),
                            "מס הכנסה מקדמות",
                            "מקדמות מס הכנסה");
                }
            }
        }

        /** Owner loan when the overdraft gets deep, dividend when cash piles up. Applied in date order. */
        void ownerReactions(BigDecimal opening, double fixed) {
            txs.sort(Comparator.comparing(BankTransaction::date));
            List<BankTransaction> reactions = new ArrayList<>();
            BigDecimal balance = opening;
            BigDecimal overdraft = money(-0.6 * fixed);
            BigDecimal rich = money(3 * fixed);
            int i = 0;
            LocalDate lastInjection = start.minusYears(1);
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                while (i < txs.size() && !txs.get(i).date().isAfter(d)) {
                    balance = balance.add(txs.get(i).amount());
                    i++;
                }
                for (BankTransaction r : reactions) {
                    if (r.date().equals(d)) {
                        balance = balance.add(r.amount());
                    }
                }
                if (balance.compareTo(overdraft) < 0 && ChronoUnit.DAYS.between(lastInjection, d) > 10) {
                    BigDecimal loan = BigDecimal.valueOf(
                                    Math.round((fixed * uniform(1.0, 2.0) - balance.doubleValue()) / 10000) * 10000L)
                            .setScale(2, RoundingMode.HALF_EVEN);
                    reactions.add(new BankTransaction(
                            BusinessCalendar.nextBusinessDay(d.plusDays(1 + rng.nextInt(3))),
                            loan,
                            "הלוואת בעלים",
                            "הלוואת בעלים"));
                    lastInjection = d;
                }
                // Dividends are a quarterly decision, and not taken every time cash is high.
                boolean quarterEnd = d.getDayOfMonth() == 25 && d.getMonthValue() % 3 == 0;
                if (quarterEnd && balance.compareTo(rich) > 0 && rng.nextDouble() < 0.7) {
                    BigDecimal dividend = Money.times(balance.subtract(money(1.5 * fixed)), 0.5)
                            .negate();
                    reactions.add(new BankTransaction(
                            BusinessCalendar.nextBusinessDay(d.plusDays(1)),
                            dividend,
                            "משיכת דיבידנד",
                            OWNER_DIVIDEND));
                }
            }
            txs.addAll(reactions);
        }

        // ---- documents ---------------------------------------------------------------------

        void invoice(Customer c, LocalDate issued, double net) {
            if (issued.isAfter(end.plusDays(120))) {
                return;
            }
            double gross = net * (1 + vatRate(issued));
            BigDecimal amount = money(gross);
            LocalDate due = c.terms().due(issued);
            addVat(issued, vatOf(gross, issued));
            addRevenue(issued, net);
            LocalDate paid = null;
            if (rng.nextDouble() >= c.payer().defaultRate()) {
                double delay = c.payer().meanDelay() + c.payer().sdDelay() * normal();
                if (rng.nextDouble() < 0.07) {
                    delay += uniform(20, 60);
                }
                LocalDate p = due.plusDays(Math.round(delay));
                paid = BusinessCalendar.nextBusinessDay(p.isBefore(issued) ? issued : p);
                BigDecimal received = c.withholding() > 0 ? amount.subtract(money(net * c.withholding())) : amount;
                add(paid, received, c.name(), "העברה מ" + c.name());
            }
            docs.add(new Document(DocumentType.INVOICE, "draft", c.name(), issued, due, amount, paid));
        }

        void bill(String vendor, LocalDate issued, LocalDate due, double gross, int delayDays) {
            BigDecimal amount = money(gross);
            addVat(issued, -vatOf(gross, issued));
            LocalDate p = due.plusDays(delayDays);
            LocalDate paid = BusinessCalendar.nextBusinessDay(p.isBefore(issued) ? issued : p);
            add(paid, amount.negate(), vendor, "תשלום לספק " + vendor);
            docs.add(new Document(DocumentType.BILL, "draft", vendor, issued, due, amount, paid));
        }

        /** Documents are created stream by stream; number them in issue order, as an accounting system would. */
        static List<Document> numberChronologically(List<Document> docs) {
            List<Document> sorted = docs.stream()
                    .sorted(Comparator.comparing(Document::issueDate))
                    .toList();
            List<Document> numbered = new ArrayList<>(sorted.size());
            int invoice = 1000;
            int bill = 1;
            for (Document d : sorted) {
                String number = d.type() == DocumentType.INVOICE ? "INV-" + invoice++ : "B-" + bill++;
                numbered.add(new Document(
                        d.type(), number, d.counterparty(), d.issueDate(), d.dueDate(), d.amount(), d.paidDate()));
            }
            return numbered;
        }

        // ---- helpers -----------------------------------------------------------------------

        void monthly(
                String party,
                String description,
                double amount,
                int dayOfMonth,
                LocalDate from,
                LocalDate to,
                boolean vat) {
            for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                LocalDate d = day(m, dayOfMonth);
                if (d.isBefore(from) || d.isAfter(to)) {
                    continue;
                }
                add(d, -amount, party, description, vat ? -vatOf(amount, d) : 0);
            }
        }

        LocalDate day(YearMonth m, int dayOfMonth) {
            return BusinessCalendar.nextBusinessDay(m.atDay(Math.min(dayOfMonth, m.lengthOfMonth())));
        }

        void add(LocalDate date, double amount, String party, String description, double vat) {
            add(date, amount, party, description, vat, true);
        }

        void add(LocalDate date, double amount, String party, String description, double vat, boolean trackVat) {
            add(date, money(amount), party, description);
            if (trackVat && vat != 0) {
                addVat(date, vat);
            }
        }

        void add(LocalDate date, BigDecimal amount, String party, String description) {
            if (amount.signum() != 0) {
                txs.add(new BankTransaction(date, amount, description, party));
            }
        }

        void addVat(LocalDate date, double vat) {
            vatByPeriod.merge(periodStart(date), money(vat), BigDecimal::add);
        }

        void addRevenue(LocalDate date, double net) {
            revenueByPeriod.merge(periodStart(date), money(net), BigDecimal::add);
        }

        static LocalDate periodStart(LocalDate date) {
            int month = ((date.getMonthValue() - 1) / 2) * 2 + 1;
            return LocalDate.of(date.getYear(), month, 1);
        }

        /** VAT inside a VAT-inclusive amount, at the rate in force on {@code date}. */
        static double vatOf(double gross, LocalDate date) {
            double r = vatRate(date);
            return gross * r / (1 + r);
        }

        static double vatRate(LocalDate date) {
            return ISRAEL_VAT.rateOn(date).doubleValue();
        }

        /** An outflow that carries input VAT (rent, utilities, purchases). */
        void addWithVat(LocalDate date, double amount, String party, String description) {
            add(date, amount, party, description, -vatOf(Math.abs(amount), date));
        }

        double seasonal(LocalDate d) {
            double f = 1.0;
            if (HebrewHolidays.inTishrei(d)) {
                f *= archetype == Archetype.RETAIL ? 1.15 : 0.65;
            }
            if (HebrewHolidays.inPesach(d)) {
                f *= archetype == Archetype.RETAIL ? 1.1 : 0.7;
            }
            switch (d.getMonth()) {
                case AUGUST -> f *= archetype == Archetype.RETAIL ? 1.1 : 0.75;
                case JULY -> f *= archetype == Archetype.RETAIL ? 1.1 : 0.9;
                case DECEMBER -> f *= archetype == Archetype.CONTRACTOR ? 0.8 : 1.1;
                case JANUARY, FEBRUARY ->
                    f *= archetype == Archetype.CONTRACTOR ? 0.8 : archetype == Archetype.RETAIL ? 0.85 : 1.0;
                default -> {}
            }
            return f;
        }

        double trend(LocalDate d) {
            double years = ChronoUnit.DAYS.between(start, d) / 365.25 - 1.5;
            return Math.pow(1 + growth, years);
        }

        Customer pickCustomer(List<Customer> customers, double totalWeight) {
            double r = rng.nextDouble() * totalWeight;
            for (Customer c : customers) {
                r -= c.weight();
                if (r <= 0) {
                    return c;
                }
            }
            return customers.getLast();
        }

        String companyName() {
            String[] first = {"Levi", "Amit", "Barak", "Dror", "Hadar", "Kedem", "Lahav", "Nitzan", "Peleg", "Shaked"};
            String kind =
                    switch (archetype) {
                        case CONSULTANCY -> "Consulting";
                        case AGENCY -> "Creative Studio";
                        case RETAIL -> "Cafe & Deli";
                        case CONTRACTOR -> "Renovations";
                    };
            return first[Math.floorMod(seed / 4, first.length)] + " " + kind + " #" + seed;
        }

        int poisson(double lambda) {
            if (lambda <= 0) {
                return 0;
            }
            double limit = Math.exp(-lambda);
            int k = 0;
            double p = rng.nextDouble();
            while (p > limit) {
                k++;
                p *= rng.nextDouble();
            }
            return k;
        }

        double uniform(double lo, double hi) {
            return lo + (hi - lo) * rng.nextDouble();
        }

        double normal() {
            double u = 1.0 - rng.nextDouble();
            double v = rng.nextDouble();
            return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        }

        double lognormal(double median, double sigma) {
            return median * Math.exp(sigma * normal());
        }

        static double round100(double x) {
            return Math.round(x / 100) * 100.0;
        }

        static BigDecimal money(double x) {
            return Money.round(BigDecimal.valueOf(x));
        }
    }
}
