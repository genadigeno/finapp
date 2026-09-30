package com.finapp.settlement;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The typed references a canonical settlement line may carry (`P8-TSK-008`, ADR-0065) — what
 * the matcher keys on (`P8-TSK-011`, ADR-0068). Typed so that a capture reference can never be
 * compared against a dispute reference by accident, and closed so a new kind is a reviewed
 * decision, never a stray string.
 */
public enum LineReferenceKind {

    /** The PSP's own capture reference — the card capture expectation's first key. */
    PSP_CAPTURE_REF,

    /** The PSP's refund reference. */
    PSP_REFUND_REF,

    /** The acquirer reference number — long, digit-shaped, and possibly Luhn-valid by chance. */
    ACQUIRER_REF,

    /** The network's dispute reference — one per dispute, across its stages. */
    DISPUTE_REF,

    /** Our own reference, where the counterparty echoes it back. */
    OUR_REF,

    /**
     * The transaction a derived or correcting line points at: a gross-plus-fee split's fee
     * line names the transaction it rode in on, and an adjustment names what it adjusts.
     */
    ORIGINAL_REF;

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
