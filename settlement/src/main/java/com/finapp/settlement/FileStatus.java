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
 * <p>`P8-TSK-002` bore files {@code RECEIVED} with no edge; `P8-TSK-008` brought the parse
 * leg and the decline (`V003`); `P8-TSK-009` brings the accept leg, so {@code ACCEPTED}
 * exists now and `V004` regenerates the {@code CHECK} and transition trigger from this enum.
 * The machine is complete: `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.1 names no
 * further status.
 */
public enum FileStatus {

    /** Born so and inert; the parse leg's claim reads exactly this status. */
    RECEIVED,

    /** The whole file became one canonical batch, in one transaction (`INV-SET-07`). */
    PARSED,

    /**
     * The accept leg recognised its batch (`P8-TSK-009`): eligible by channel — a pull by
     * its credential, an upload by its second person — sequenced gaplessly, its fees posted
     * and its run handed over, all in one transaction. Terminal: a repudiated BATCH leaves
     * its file {@code ACCEPTED} — the evidence is retained (§5.1).
     */
    ACCEPTED,

    /**
     * A content defect rejected the whole file, a person declined it, or its source retired
     * before acceptance — terminal. Our own failure never lands here: a parser exception
     * leaves {@code RECEIVED} with its back-off (ADR-0066 §9).
     */
    REJECTED;

    /** The states reachable from this one — the trigger edges are generated from it. */
    public Set<FileStatus> permittedTransitions() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(PARSED, REJECTED);
            // The accept leg's edge and the two PARSED rejections (a decline;
            // SOURCE_RETIRED), each with its producer (`P8-TSK-008`, `-009`).
            case PARSED -> EnumSet.of(ACCEPTED, REJECTED);
            case ACCEPTED, REJECTED -> EnumSet.noneOf(FileStatus.class);
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
