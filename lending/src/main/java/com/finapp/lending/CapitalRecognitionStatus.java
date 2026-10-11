package com.finapp.lending;

/**
 * Whether an approved contribution became capital - a DERIVED condition, never stored here (`P11-TSK-004`, ADR-0096
 * section 4): read from reconciliation's expectation and its recognition through {@link CapitalRecognitions}, on the
 * database's clock.
 */
public enum CapitalRecognitionStatus {

    /** Approved; its transfer is not yet on an accepted statement, and its window is open. */
    AWAITING_FUNDS,

    /** Bank recognition matched its statement line and credited {@code LENDING_CAPITAL} (rule 0). */
    RECOGNISED,

    /**
     * Its window passed with no statement line recognised: it never becomes capital - recognition refuses a lapsed
     * contribution on the same database clock, so the report is final. Money that arrives later is an unattributed
     * statement line, a break like any other.
     */
    LAPSED;

    /** The derivation: recognised if a recognition exists; else lapsed once the database's date passes the window. */
    public static CapitalRecognitionStatus derive(boolean recognised, java.time.LocalDate expectedBy, java.time.LocalDate today) {
        if (recognised) {
            return RECOGNISED;
        }
        return today.isAfter(expectedBy) ? LAPSED : AWAITING_FUNDS;
    }
}
