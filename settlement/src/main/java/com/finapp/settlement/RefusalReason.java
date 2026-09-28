package com.finapp.settlement;

/**
 * Why a delivery was refused at the door (`P8-TSK-002`, ADR-0066 §3, §4).
 *
 * <p>The two content reasons leave a {@code settlement.refused_delivery} metadata row — never
 * the value — and are recovered by re-presentation. The two bound reasons store nothing at all
 * beyond their audit record, because parse and acceptance are each one transaction and the
 * bounds exist to keep those transactions finite. All four are the
 * {@code finapp.settlement.delivery.refused} counter's {@code outcome} vocabulary, alertable
 * from the first file.
 */
public enum RefusalReason {

    /** A Luhn-valid 13–19-digit run in screened text ({@code INV-PAY-02}: refused, never held). */
    PRIMARY_ACCOUNT_NUMBER,

    /** An international account identifier or alias shape ({@code INV-RAIL-03}'s surface). */
    ACCOUNT_IDENTIFIER,

    /** Decoded content outside 1..8 MiB — nothing stored but the audit record. */
    FILE_TOO_LARGE,

    /** More than 50,000 records by the screen's own walk — nothing stored but the audit record. */
    TOO_MANY_LINES;

    /** Whether this refusal leaves a {@code refused_delivery} metadata row. */
    public boolean leavesARow() {
        return this == PRIMARY_ACCOUNT_NUMBER || this == ACCOUNT_IDENTIFIER;
    }
}
