package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The suspense item's machine (`P8-TSK-010`, ADR-0070 §1,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.8): released only ever forward, with
 * {@code released_minor} the one moving amount and the status its mirror — the generated
 * {@code CHECK} binds them ({@code OPEN} ⇔ nothing released, {@code RELEASED} ⇔ all of it),
 * so the status can never claim what the amounts refute.
 */
public enum SuspenseItemStatus {

    /** Born so, in the transaction that records its owning break ({@code INV-REC-09}). */
    OPEN,

    /** Part of the amount released — the break stays open over the remainder. */
    PARTIALLY_RELEASED,

    /** Terminal: {@code released_minor} reached {@code amount_minor}. */
    RELEASED;

    /** The states reachable from this one — `V004`'s trigger edges are generated from it. */
    public Set<SuspenseItemStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(PARTIALLY_RELEASED, RELEASED);
            case PARTIALLY_RELEASED -> EnumSet.of(RELEASED);
            case RELEASED -> EnumSet.noneOf(SuspenseItemStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The `V004` transition trigger's edge condition — reconciled by the migration test. */
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
