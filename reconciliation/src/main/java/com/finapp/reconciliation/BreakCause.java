package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which detector raised the break (`P8-TSK-010`, ADR-0069 §2's causes, closed here as that
 * ADR instructs) — frozen with the break: a reclassified break still says how it was found,
 * so the type–cause pairing binds the RAISE (a `V004` {@code BEFORE INSERT} trigger,
 * generated from {@link #raisesAs()}), never the row for life — a table {@code CHECK} would
 * refuse the reclassification ADR-0069 §7 allows. An investigator's reclassification raises
 * nothing, so it has no member here.
 */
public enum BreakCause {

    /** The ageing sweep: nothing allocated past {@code expected_by + SETTLEMENT_DATE_DAYS}. */
    EXPECTATION_OVERDUE(BreakType.MISSING_EXTERNAL),

    /** The grace leg: the window passed with the remainder unexplained. */
    GRACE_EXPIRED(BreakType.MISSING_INTERNAL, BreakType.UNKNOWN_EXTERNAL),

    /** An unmatched confirmation parked on receipt, through the port (`P8-TSK-020`). */
    PARKED_ON_RECEIPT(BreakType.UNKNOWN_EXTERNAL),

    /** A bank line no remittance pattern attributes, at recognition (`P8-TSK-016`). */
    BANK_LINE_UNATTRIBUTED(BreakType.UNKNOWN_EXTERNAL),

    /** A one-to-one match with a different amount (`P8-TSK-011`). */
    AMOUNT_DIFFERS(BreakType.AMOUNT_MISMATCH),

    /** A key hit in another currency (`P8-TSK-011`). */
    CURRENCY_DIFFERS(BreakType.CURRENCY_MISMATCH),

    /** The fee check beyond its tolerance (`P8-TSK-012`). */
    FEE_BEYOND_TOLERANCE(BreakType.FEE_MISMATCH),

    /** The expectation was already fully allocated (`P8-TSK-011`). */
    EXPECTATION_EXHAUSTED(BreakType.DUPLICATE_EXTERNAL),

    /** A canonical fingerprint seen before (`P8-TSK-011`). */
    REPEATED_FINGERPRINT(BreakType.DUPLICATE_EXTERNAL),

    /** A key collision recorded at opening — this task's own leg. */
    KEY_COLLISION(BreakType.DUPLICATE_INTERNAL),

    /** Two or more candidates (`P8-TSK-011`). */
    MULTIPLE_CANDIDATES(BreakType.AMBIGUOUS_MATCH),

    /** Matched later than the window (`P8-TSK-011`). */
    LATE_MATCH(BreakType.TIMING_DIFFERENCE),

    /** Settled in a cycle other than the announced one (`P8-TSK-017`). */
    CYCLE_MISMATCH(BreakType.TIMING_DIFFERENCE),

    /** A line whose direction contradicts the record (`P8-TSK-011`). */
    DIRECTION_CONTRADICTED(BreakType.REVERSAL_MISMATCH),

    /** A capture on a voided or failed attempt; a reversal without a win (`P8-TSK-011`). */
    TERMINAL_STATE_CONTRADICTED(BreakType.REVERSAL_MISMATCH),

    /** A payout return that could not be applied (`P8-TSK-018`, ADR-0073). */
    RETURN_NOT_APPLICABLE(BreakType.REVERSAL_MISMATCH),

    /** A refund line against a refund not completed, or a capture with none (`P8-TSK-011`). */
    REFUND_CONTRADICTED(BreakType.REFUND_MISMATCH),

    /** The bank unequal to the remittance (`P8-TSK-016`). */
    REMITTANCE_DIFFERS(BreakType.SETTLEMENT_MISMATCH),

    /** A statement-sequence gap, or an opening unequal to the previous closing (`-016`). */
    STATEMENT_GAP(BreakType.SETTLEMENT_MISMATCH),

    /** The first statement opens at other than zero (`P8-TSK-016`; owner decision O4). */
    OPENING_BALANCE(BreakType.SETTLEMENT_MISMATCH),

    /** An item the chunk could not process (`P8-TSK-011`). */
    ITEM_ERRORED(BreakType.PROCESSING_ERROR),

    /** N consecutive chunk failures blocked the run (`P8-TSK-011`, `-013`). */
    RUN_BLOCKED(BreakType.PROCESSING_ERROR),

    /** A decision replay diverged from the record (`P8-TSK-022`). */
    REPLAY_DIVERGED(BreakType.PROCESSING_ERROR),

    /** A repudiation reversed evidence a resolution had already acted on (`P8-TSK-023`). */
    EVIDENCE_REPUDIATED(BreakType.PROCESSING_ERROR),

    /**
     * A Phase 7 parking whose scheme execution a credit, a withdrawal or a return already
     * explains — one payments `V023`'s backfill left unclaimed — adopted as the duplicate it is
     * (`P8-TSK-020`, ADR-0070's recorded design input): its value must never be attributed a
     * second time, so the break admits no transfer. Appended by reconciliation `V011`.
     */
    EXECUTION_ALREADY_EXPLAINED(BreakType.DUPLICATE_EXTERNAL),

    /**
     * An FX leg settled for another amount than the cover executed (`P9-TSK-011`, PHASE_9_PLAN.md
     * section 12.9.3): reconciliation never converts, so a rate difference IS a leg's amount
     * mismatch. Selected by the expectation's kind in matching (`P9-TSK-013`).
     */
    FX_LEG_DIFFERS(BreakType.AMOUNT_MISMATCH),

    /**
     * An FX leg settled on another value date than the cover expected, beyond the source's
     * {@code SETTLEMENT_DATE_DAYS}: a timing detector's cause, one person's zero-value acknowledgement.
     */
    VALUE_DATE_DIFFERS(BreakType.TIMING_DIFFERENCE);

    private final Set<BreakType> raisesAs;

    BreakCause(BreakType first, BreakType... rest) {
        this.raisesAs = EnumSet.of(first, rest);
    }

    /** The types this detector may raise — the raise-time pairing, generated into `V004`. */
    public Set<BreakType> raisesAs() {
        return EnumSet.copyOf(raisesAs);
    }

    /** The causes `V004` admitted; {@link #EXECUTION_ALREADY_EXPLAINED} arrived with `V011`. */
    public static Set<BreakCause> v004Vocabulary() {
        return EnumSet.range(EXPECTATION_OVERDUE, EVIDENCE_REPUDIATED);
    }

    /** The whole {@code CHECK} value list (`V011`) — reconciled by the migration test. */
    public static String sqlValueList() {
        return sqlValueList(EnumSet.allOf(BreakCause.class));
    }

    /** The value list of {@code members}, in declaration order. */
    public static String sqlValueList(Set<BreakCause> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The whole raise-pairing trigger rule (`V011`) — reconciled by the migration test. */
    public static String sqlRaisePairingRule() {
        return sqlRaisePairingRule(EnumSet.allOf(BreakCause.class));
    }

    /** The raise-pairing rule over {@code members}, in declaration order. */
    public static String sqlRaisePairingRule(Set<BreakCause> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(
                        cause ->
                                "(NEW.cause = '" + cause.name() + "' AND NEW.type IN ("
                                        + cause.raisesAs.stream()
                                                .map(type -> "'" + type.name() + "'")
                                                .collect(Collectors.joining(", "))
                                        + "))")
                .collect(Collectors.joining(" OR "));
    }
}
