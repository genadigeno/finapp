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
    ORIGINAL_REF,

    /**
     * The structured remittance reference a bank statement line carries (`P8-TSK-016`) — what
     * attribution normalises the line by, and what the matcher reaches the attributed
     * counterparty's {@code REMITTANCE} by. Never a name or an account identifier.
     */
    REMITTANCE_REF,

    /**
     * The instant scheme's own reference for an execution (`P8-TSK-017`) — the key its
     * {@code payments.scheme_execution_claim} gives exactly one subject.
     */
    SCHEME_REF,

    /** The end-to-end reference the platform gave a push execution, echoed by the scheme. */
    END_TO_END_REF;

    /** The report vocabulary `V003` admitted; {@code REMITTANCE_REF} arrived with `V005`. */
    public static java.util.Set<LineReferenceKind> reportVocabulary() {
        return java.util.EnumSet.range(PSP_CAPTURE_REF, ORIGINAL_REF);
    }

    /** The vocabulary through the bank statement — what `V005` admitted. */
    public static java.util.Set<LineReferenceKind> bankVocabulary() {
        return java.util.EnumSet.range(PSP_CAPTURE_REF, REMITTANCE_REF);
    }

    /** A subset's {@code CHECK} value list, in declaration order. */
    public static String sqlValueList(java.util.Set<LineReferenceKind> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The whole {@code CHECK} value list (`V006`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
