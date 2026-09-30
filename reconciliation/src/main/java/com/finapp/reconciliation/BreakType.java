package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The fourteen break types, closed (`P8-TSK-010`, ADR-0069 §2) — a fifteenth is an ADR
 * amendment and a migration. Each row of ADR-0069's table is the one authority on what the
 * type means, which subjects it stands on, whether its value parks and which resolution
 * kinds it admits; this enum carries only what the records and the database rules need:
 * the closed list, and whether the type may own a suspense item ({@code INV-REC-09}'s type
 * discipline, ADR-0070 §2 — generated into `V004`'s owner-type trigger).
 *
 * <p>The base severity lives in {@link BreakSeverity}, the one seat of the whole grading
 * rule (ADR-0069 §5).
 */
public enum BreakType {

    /** An internal record missing externally: an expectation aged past its window. */
    MISSING_EXTERNAL(false),

    /** External known; the internal operation exists but has not completed. */
    MISSING_INTERNAL(true),

    /** An external transaction nothing internal names — possibly misdirected money. */
    UNKNOWN_EXTERNAL(true),

    /** A one-to-one match with a different amount; the over-part parks. */
    AMOUNT_MISMATCH(true),

    /** A key hit in another currency — never converted ({@code INV-MON-04}). */
    CURRENCY_MISMATCH(true),

    /** A reported fee beyond its tolerance — commercial; already expensed. */
    FEE_MISMATCH(false),

    /** The expectation already fully allocated, or a repeated fingerprint. */
    DUPLICATE_EXTERNAL(true),

    /** A question about our own records: a key collision recorded at opening. */
    DUPLICATE_INTERNAL(false),

    /** Two or more candidates — the engine refuses to guess. */
    AMBIGUOUS_MATCH(true),

    /** Late beyond tolerance, or the wrong cycle; value zero. */
    TIMING_DIFFERENCE(false),

    /** A direction contradicting the record; a return that cannot apply. */
    REVERSAL_MISMATCH(true),

    /** A refund line against a refund the platform does not hold as completed. */
    REFUND_MISMATCH(true),

    /** The bank against the remittance, or the statement chain itself. */
    SETTLEMENT_MISMATCH(true),

    /** Our own defect: an errored item, a blocked run, a diverged replay. */
    PROCESSING_ERROR(true);

    private final boolean mayOwnSuspense;

    BreakType(boolean mayOwnSuspense) {
        this.mayOwnSuspense = mayOwnSuspense;
    }

    /**
     * Whether a suspense item may name a break of this type as its owner (ADR-0070 §2):
     * {@code MISSING_EXTERNAL}, {@code FEE_MISMATCH}, {@code DUPLICATE_INTERNAL} and
     * {@code TIMING_DIFFERENCE} never own suspense — their value is in no position, or
     * stays in its own.
     */
    public boolean mayOwnSuspense() {
        return mayOwnSuspense;
    }

    /**
     * Whether this type stands on {@code subject} — ADR-0069 §2's Subject column, the one
     * authority (`P8-TSK-014`): a reclassification must land on a type that admits the
     * break's own subject kind, because the subject is frozen at raise.
     */
    public boolean admits(BreakSubjectKind subject) {
        return switch (this) {
            case MISSING_EXTERNAL, DUPLICATE_INTERNAL -> subject == BreakSubjectKind.EXPECTATION;
            case UNKNOWN_EXTERNAL ->
                    subject == BreakSubjectKind.EXTERNAL_ITEM
                            || subject == BreakSubjectKind.SUSPENSE_ITEM;
            case AMOUNT_MISMATCH ->
                    subject == BreakSubjectKind.EXPECTATION
                            || subject == BreakSubjectKind.EXTERNAL_ITEM;
            // A parking whose execution is already explained is a duplicate held as a
            // suspense item, never an external item (P8-TSK-020, ADR-0069 section 2 amended).
            case DUPLICATE_EXTERNAL ->
                    subject == BreakSubjectKind.EXTERNAL_ITEM
                            || subject == BreakSubjectKind.SUSPENSE_ITEM;
            case MISSING_INTERNAL,
                            CURRENCY_MISMATCH,
                            FEE_MISMATCH,
                            AMBIGUOUS_MATCH,
                            REVERSAL_MISMATCH,
                            REFUND_MISMATCH ->
                    subject == BreakSubjectKind.EXTERNAL_ITEM;
            case TIMING_DIFFERENCE -> subject == BreakSubjectKind.DECISION;
            case SETTLEMENT_MISMATCH ->
                    subject == BreakSubjectKind.EXPECTATION
                            || subject == BreakSubjectKind.EXTERNAL_ITEM
                            || subject == BreakSubjectKind.RUN;
            case PROCESSING_ERROR ->
                    subject == BreakSubjectKind.EXTERNAL_ITEM
                            || subject == BreakSubjectKind.RUN
                            || subject == BreakSubjectKind.DECISION;
        };
    }

    /**
     * Whether a break of this type on {@code subject} holds parked value — ADR-0069 §2's
     * Parked column read per subject: an item's remainder parks exactly for the
     * suspense-owning types, a suspense item IS parked value, and an expectation's, a run's
     * or a decision's never does (its value stays in its position, or is zero). A
     * reclassification must keep this equal to what the break's subject actually holds
     * (ADR-0069 §7), or a parked item's owner could become a type whose closure leaves its
     * suspense behind ({@code INV-REC-09}).
     */
    public boolean parksOn(BreakSubjectKind subject) {
        return switch (subject) {
            case EXTERNAL_ITEM -> mayOwnSuspense;
            case SUSPENSE_ITEM -> true;
            case EXPECTATION, RUN, DECISION -> false;
        };
    }

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The `V004` owner-type trigger's list — the types that may own a suspense item. */
    public static String sqlSuspenseOwningList() {
        return Arrays.stream(values())
                .filter(BreakType::mayOwnSuspense)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
