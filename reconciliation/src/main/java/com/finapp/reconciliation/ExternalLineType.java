package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * An external item's line type (`P8-TSK-009`) — reconciliation's MIRROR of settlement's
 * canonical vocabulary, name for name, because an item is the working copy of one immutable
 * settlement line and no build edge may exist between the modules (ADR-0064). The copies
 * are held equal by an `app` guard test, which is the one place that sees both.
 *
 * <p>{@link #allocating()} is the matcher's and the proof's split (ADR-0065 §2,
 * `INV-REC-06`): a {@code PROCESSING_FEE} line's effect IS the recognition entry, so it
 * never allocates against an expectation and never enters the position identity's items
 * term — it takes the {@code CHECKED} path at matching. A {@code BANK_FEE} is the same fact at
 * the bank (`P8-TSK-016`: posted DR {@code PROCESSING_COSTS} at the statement's recognition), and a
 * {@code SCHEME_FEE} the same fact in the instant scheme's cycle report (`P8-TSK-017`).
 * Everything else asserts value already in the position and allocates.
 */
public enum ExternalLineType {
    CAPTURE,
    REFUND,
    CHARGEBACK,
    CHARGEBACK_REVERSAL,
    DISPUTE_FEE,
    PROCESSING_FEE,
    COUNTERPARTY_ADJUSTMENT,
    OTHER_IN,
    OTHER_OUT,
    BANK_CREDIT,
    BANK_DEBIT,
    BANK_FEE,
    CREDIT_IN,
    DEBIT_OUT,
    SCHEME_FEE;

    /** Whether this line claims value in the position — false exactly for the fees. */
    public boolean allocating() {
        return this != PROCESSING_FEE && this != BANK_FEE && this != SCHEME_FEE;
    }

    /**
     * The key a report fee's {@code ORIGINAL_REF} reaches its transaction's expectation by
     * (`P8-TSK-017`): the PSP's fee names a capture, the scheme's an execution. Empty for every
     * other line — the bank's fee has no original (it is judged against a zero gross).
     */
    public java.util.Optional<KeyKind> originalKeyKind() {
        return switch (this) {
            case PROCESSING_FEE -> java.util.Optional.of(KeyKind.PSP_CAPTURE_REF);
            case SCHEME_FEE -> java.util.Optional.of(KeyKind.SCHEME_REF);
            default -> java.util.Optional.empty();
        };
    }

    /** Whether this is a bank statement's line (`P8-TSK-016`). */
    public boolean isBankLine() {
        return this == BANK_CREDIT || this == BANK_DEBIT || this == BANK_FEE;
    }

    /** The report vocabulary `V003` admitted; the bank members arrived with `V008`. */
    public static java.util.Set<ExternalLineType> reportVocabulary() {
        return java.util.EnumSet.range(CAPTURE, OTHER_OUT);
    }

    /** The vocabulary through the bank statement — what `V008` admitted. */
    public static java.util.Set<ExternalLineType> bankVocabulary() {
        return java.util.EnumSet.range(CAPTURE, BANK_FEE);
    }

    /** A subset's {@code CHECK} value list, in declaration order. */
    public static String sqlValueList(java.util.Set<ExternalLineType> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The whole {@code CHECK} value list (`V009`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The allocating members as a SQL list — the items-term reading's filter. */
    public static String sqlAllocatingList() {
        return Arrays.stream(values())
                .filter(ExternalLineType::allocating)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
