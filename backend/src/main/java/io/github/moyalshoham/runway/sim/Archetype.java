package io.github.moyalshoham.runway.sim;

/** Kinds of synthetic business, each with its own revenue mix, cost structure and seasonality. */
public enum Archetype {
    /** Few clients, monthly retainers plus projects, mostly payroll costs. */
    CONSULTANCY,
    /** Project invoices to corporates on long terms (EOM+60/90), freelancer bills. */
    AGENCY,
    /** Daily card sales, weekly supplier bills, almost no invoices. */
    RETAIL,
    /** Few large milestone invoices, large materials and subcontractor bills. */
    CONTRACTOR
}
