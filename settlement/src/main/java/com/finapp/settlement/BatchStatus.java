package com.finapp.settlement;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The settlement batch's machine (`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.2) — whole
 * since `P8-TSK-023`.
 *
 * <p>A batch is born {@code PARSED} in its file's {@code RECEIVED → PARSED} transaction;
 * the accept leg moves it {@code PARSED → ACCEPTED} with its acceptance facts (`P8-TSK-009`,
 * `V004`), and {@code PARSED → REJECTED} rides its file's rejection (the decline;
 * `SOURCE_RETIRED`). {@code ACCEPTED → REPUDIATED} is an approved {@code REPUDIATE_BATCH}
 * resolution's (`P8-TSK-023`, settlement `V010`, which regenerated the {@code CHECK} and
 * trigger once more) — the live unique's status list was written whole by `V003`, naming it
 * already, so no index changed, and the genuine file is admitted as a new batch.
 */
public enum BatchStatus {

    /** Born so, whole: lines, references and totals committed with it (`INV-SET-07`). */
    PARSED,

    /**
     * Recognised once (`INV-SET-04`): sequenced gaplessly, its fees posted, its run and
     * items handed to reconciliation — final but for the one designed exit, a repudiation
     * (`P8-TSK-023`).
     */
    ACCEPTED,

    /** Its file was rejected after parsing — terminal, and its live key frees. */
    REJECTED,

    /**
     * Proven fabricated or mis-normalised after acceptance (`P8-TSK-023`, ADR-0065 §10): an
     * approved four-eyes {@code REPUDIATE_BATCH} moved it, its recognition reversed — terminal,
     * its acceptance facts kept as they were, its live key freed for the genuine file.
     */
    REPUDIATED;

    /** The states reachable from this one — the trigger edges are generated from it. */
    public Set<BatchStatus> permittedTransitions() {
        return switch (this) {
            case PARSED -> EnumSet.of(ACCEPTED, REJECTED);
            case ACCEPTED -> EnumSet.of(REPUDIATED);
            case REJECTED, REPUDIATED -> EnumSet.noneOf(BatchStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The `V010` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The transition trigger's edge condition (`V010`) — reconciled by the migration test. */
    public static String sqlTransitionRule() {
        return Arrays.stream(values())
                .filter(from -> !from.permittedTransitions().isEmpty())
                .map(
                        from ->
                                "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                                        + from.permittedTransitions().stream()
                                                .map(to -> "'" + to.name() + "'")
                                                .collect(Collectors.joining(", "))
                                        + "))")
                .collect(Collectors.joining(" OR "));
    }
}
