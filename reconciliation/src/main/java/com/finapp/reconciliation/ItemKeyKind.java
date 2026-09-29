package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The typed references an external item carries (`P8-TSK-009`) — reconciliation's MIRROR of
 * settlement's line-reference vocabulary, name for name (the {@link ExternalLineType}
 * reasoning: the item copies the line, and ADR-0064 forbids the build edge). Held equal by
 * the same `app` guard.
 *
 * <p>Deliberately NOT {@link KeyKind}: that is the INTERNAL side's vocabulary (what our
 * expectations are reachable by, per stage and rail); this is the EXTERNAL side's (what the
 * counterparty wrote on the line). Which internal kind an external reference is matched
 * against is the versioned rule set's decision (`P8-TSK-011`, ADR-0068) — a
 * {@code DISPUTE_REF} on a {@code CHARGEBACK} line resolves to {@code DISPUTE_CB_REF}, on a
 * reversal to {@code DISPUTE_REV_REF} — and {@code ORIGINAL_REF} is the correction rule's
 * item-to-item pointer, which no expectation ever keys.
 */
public enum ItemKeyKind {
    PSP_CAPTURE_REF,
    PSP_REFUND_REF,
    ACQUIRER_REF,
    DISPUTE_REF,
    OUR_REF,
    ORIGINAL_REF;

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
