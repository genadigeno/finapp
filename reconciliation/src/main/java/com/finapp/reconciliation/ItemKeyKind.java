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
    ORIGINAL_REF,
    /** A bank statement line's structured remittance reference (`P8-TSK-016`). */
    REMITTANCE_REF,
    /** The instant scheme's reference for an execution (`P8-TSK-017`). */
    SCHEME_REF,
    /** The platform's end-to-end reference for a push execution, echoed by the scheme. */
    END_TO_END_REF,
    /** The payout provider's own reference for a payout it accepted (`P8-TSK-018`). */
    PAYOUT_PROVIDER_REF;

    /** The report vocabulary `V003` admitted; {@code REMITTANCE_REF} arrived with `V008`. */
    public static java.util.Set<ItemKeyKind> reportVocabulary() {
        return java.util.EnumSet.range(PSP_CAPTURE_REF, ORIGINAL_REF);
    }

    /** The vocabulary through the bank statement — what `V008` admitted. */
    public static java.util.Set<ItemKeyKind> bankVocabulary() {
        return java.util.EnumSet.range(PSP_CAPTURE_REF, REMITTANCE_REF);
    }

    /** The vocabulary through the scheme's cycle report — what `V009` admitted. */
    public static java.util.Set<ItemKeyKind> schemeVocabulary() {
        return java.util.EnumSet.range(PSP_CAPTURE_REF, END_TO_END_REF);
    }

    /** A subset's {@code CHECK} value list, in declaration order. */
    public static String sqlValueList(java.util.Set<ItemKeyKind> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The whole {@code CHECK} value list (`V010`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
