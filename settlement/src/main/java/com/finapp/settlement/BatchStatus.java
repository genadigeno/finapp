package com.finapp.settlement;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The settlement batch's machine (`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.2) — of
 * which `P8-TSK-008` produces birth and one exit.
 *
 * <p>A batch is born {@code PARSED} in its file's {@code RECEIVED → PARSED} transaction;
 * the accept leg moves it {@code PARSED → ACCEPTED} with its acceptance facts (`P8-TSK-009`,
 * `V004`), and {@code PARSED → REJECTED} rides its file's rejection (the decline;
 * `SOURCE_RETIRED`). {@code REPUDIATED} arrives with its producer (`P8-TSK-023`),
 * regenerating the {@code CHECK} and trigger once more — but the live unique's status list
 * was written whole by `V003`, naming it already, so `-023` changes no index.
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
    REJECTED;

    /** The states reachable from this one — the trigger edges are generated from it. */
    public Set<BatchStatus> permittedTransitions() {
        return switch (this) {
            case PARSED -> EnumSet.of(ACCEPTED, REJECTED);
            // ACCEPTED -> REPUDIATED joins with its producer (`P8-TSK-023`).
            case ACCEPTED, REJECTED -> EnumSet.noneOf(BatchStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The `V003` transition trigger's edge condition — reconciled by the migration test. */
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
