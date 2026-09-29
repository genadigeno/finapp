package com.finapp.settlement;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The settlement file's machine (`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.1) — grown
 * edge by edge with each edge's producer, the three-layer discipline's rule that a table
 * cannot honestly precede its machine.
 *
 * <p>`P8-TSK-002` bore files {@code RECEIVED} with no edge. `P8-TSK-008` brings the parse leg
 * and the decline, so {@code PARSED} and {@code REJECTED} exist now and `V003` regenerates
 * the {@code CHECK} and transition trigger from this enum. {@code ACCEPTED} arrives with the
 * accept leg (`P8-TSK-009`), which regenerates them again with the one edge it produces.
 */
public enum FileStatus {

    /** Born so and inert; the parse leg's claim reads exactly this status. */
    RECEIVED,

    /** The whole file became one canonical batch, in one transaction (`INV-SET-07`). */
    PARSED,

    /**
     * A content defect rejected the whole file, or a person declined it — terminal. Our own
     * failure never lands here: a parser exception leaves {@code RECEIVED} with its back-off
     * (ADR-0066 §9).
     */
    REJECTED;

    /** The states reachable from this one — `V003`'s trigger edges are generated from it. */
    public Set<FileStatus> permittedTransitions() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(PARSED, REJECTED);
            // ACCEPTED joins with its producer (`P8-TSK-009`); the decline and SOURCE_RETIRED
            // are the PARSED exits that exist today.
            case PARSED -> EnumSet.of(REJECTED);
            case REJECTED -> EnumSet.noneOf(FileStatus.class);
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
