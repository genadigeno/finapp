package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a decision's engine concluded (`P8-TSK-022`, ADR-0068 §9, `V012`): stored on every
 * {@code match_decision} so replay compares conclusions exactly — {@code PARKED} alone covers
 * five different conclusions. Each value names the pure function that produced it, which is
 * how replay knows which function to re-run: {@link MatchEngine}'s verdicts, {@link GroupMatch}'s
 * prefixed {@code GROUP_}, {@link CorrectionEngine}'s, {@link FeeCheck}'s within or beyond its
 * tolerance; a person's {@code MANUAL_CHOICE}; and {@code ERRORED}, a contained exception —
 * nothing pure to re-run.
 */
public enum DecisionVerdict {
    ALLOCATE,
    AMBIGUOUS,
    DIRECTION_CONTRADICTED,
    CURRENCY_CONTRADICTED,
    DUPLICATE,
    NO_CANDIDATES,
    NO_RULE,
    GROUP_MATCH,
    GROUP_NO_CANDIDATES,
    GROUP_TOTAL_DIFFERS,
    GROUP_MEMBERSHIP_MOVED,
    TOP_UP,
    OFFSET,
    CORRECTION_UNREACHED,
    FEE_WITHIN,
    FEE_BEYOND,
    MANUAL_CHOICE,
    ERRORED;

    /** The pure function a verdict came from, which replay re-runs. */
    public enum Engine {
        MATCH,
        GROUP,
        CORRECTION,
        FEE,
        MANUAL,
        CONTAINED
    }

    public Engine engine() {
        return switch (this) {
            case ALLOCATE, AMBIGUOUS, DIRECTION_CONTRADICTED, CURRENCY_CONTRADICTED, DUPLICATE,
                    NO_CANDIDATES, NO_RULE -> Engine.MATCH;
            case GROUP_MATCH, GROUP_NO_CANDIDATES, GROUP_TOTAL_DIFFERS,
                    GROUP_MEMBERSHIP_MOVED -> Engine.GROUP;
            case TOP_UP, OFFSET, CORRECTION_UNREACHED -> Engine.CORRECTION;
            case FEE_WITHIN, FEE_BEYOND -> Engine.FEE;
            case MANUAL_CHOICE -> Engine.MANUAL;
            case ERRORED -> Engine.CONTAINED;
        };
    }

    public static DecisionVerdict of(MatchEngine.VerdictKind kind) {
        return switch (kind) {
            case ALLOCATE -> ALLOCATE;
            case AMBIGUOUS -> AMBIGUOUS;
            case DIRECTION_CONTRADICTED -> DIRECTION_CONTRADICTED;
            case CURRENCY_CONTRADICTED -> CURRENCY_CONTRADICTED;
            case DUPLICATE -> DUPLICATE;
            case NO_CANDIDATES -> NO_CANDIDATES;
            case NO_RULE -> NO_RULE;
        };
    }

    /**
     * A value-date group's verdict. A moved membership concludes nothing, but the run's chunk
     * records the line's wait with what it saw; grace writes nothing for it.
     */
    public static DecisionVerdict of(GroupMatch.Kind kind) {
        return switch (kind) {
            case MATCH -> GROUP_MATCH;
            case NO_CANDIDATES -> GROUP_NO_CANDIDATES;
            case TOTAL_DIFFERS -> GROUP_TOTAL_DIFFERS;
            case MEMBERSHIP_MOVED -> GROUP_MEMBERSHIP_MOVED;
        };
    }

    public static DecisionVerdict of(CorrectionEngine.Kind kind) {
        return switch (kind) {
            case TOP_UP -> TOP_UP;
            case OFFSET -> OFFSET;
            case UNREACHED -> CORRECTION_UNREACHED;
        };
    }

    public static DecisionVerdict fee(boolean beyondTolerance) {
        return beyondTolerance ? FEE_BEYOND : FEE_WITHIN;
    }

    /** The matching engine's verdicts: their decisions store the fingerprint input. */
    public static Set<DecisionVerdict> matchEngine() {
        return EnumSet.of(
                ALLOCATE, AMBIGUOUS, DIRECTION_CONTRADICTED, CURRENCY_CONTRADICTED, DUPLICATE,
                NO_CANDIDATES, NO_RULE);
    }

    /** The `V012` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return sqlValueList(EnumSet.allOf(DecisionVerdict.class));
    }

    /** The value list of {@code members}, in declaration order. */
    public static String sqlValueList(Set<DecisionVerdict> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
