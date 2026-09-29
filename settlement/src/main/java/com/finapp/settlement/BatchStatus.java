package com.finapp.settlement;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The settlement batch's machine (`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.2) — of
 * which `P8-TSK-008` produces birth and one exit.
 *
 * <p>A batch is born {@code PARSED} in its file's {@code RECEIVED → PARSED} transaction and
 * moves {@code PARSED → REJECTED} only with its file (the decline; `SOURCE_RETIRED` joins with
 * the accept leg). {@code ACCEPTED} and {@code REPUDIATED} arrive with their producers
 * (`P8-TSK-009`, `P8-TSK-023`), each regenerating `V003`'s {@code CHECK} and trigger — but the
 * live unique's status list is written whole NOW, naming {@code REPUDIATED} too, so `-023`
 * changes no index (the backlog's own instruction).
 */
public enum BatchStatus {

    /** Born so, whole: lines, references and totals committed with it (`INV-SET-07`). */
    PARSED,

    /** Its file was rejected after parsing (a decline today) — terminal, and its live key frees. */
    REJECTED;

    /** The states reachable from this one — `V003`'s trigger edges are generated from it. */
    public Set<BatchStatus> permittedTransitions() {
        return switch (this) {
            case PARSED -> EnumSet.of(REJECTED);
            case REJECTED -> EnumSet.noneOf(BatchStatus.class);
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
