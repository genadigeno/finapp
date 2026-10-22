package com.finapp.risk;

/**
 * The other side of a monitored transaction (`P13-TSK-019`): the ledger's owner kinds by value - the journal names an
 * account's owner as {@code CUSTOMER}, {@code MERCHANT}, {@code LOAN}, {@code BNPL_OBLIGATION}, {@code COUNTERPARTY},
 * {@code OPERATIONAL} or {@code SUSPENSE} ({@code ledger_account.owner_kind}, carried through the port as a bounded
 * code, never as the ledger's type) - and {@link #NONE} for an entry with no line off the subject's own account.
 */
public enum MonitoredCounterpartyKind {
    CUSTOMER,
    MERCHANT,
    LOAN,
    BNPL_OBLIGATION,
    COUNTERPARTY,
    OPERATIONAL,
    SUSPENSE,
    NONE;

    /** The ledger's owner kind by value; an unknown code is {@link #OPERATIONAL} - never a refusal of the entry. */
    public static MonitoredCounterpartyKind ofOwnerKind(String ownerKind) {
        for (MonitoredCounterpartyKind kind : values()) {
            if (kind != NONE && kind.name().equals(ownerKind)) {
                return kind;
            }
        }
        return OPERATIONAL;
    }
}
