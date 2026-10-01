package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The external item's matching status (`P8-TSK-009`,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.4) — every edge of which now has its
 * producer.
 *
 * <p>`P8-TSK-009` births {@code PENDING} rows, one per accepted settlement line; the run,
 * rematch and grace legs drive the matching edges (`-011`/`-013`), the reprocess leg its
 * {@code UNMATCHED} and {@code PARKED} edges (`-022`), the resolutions {@code PARKED →
 * RESOLVED} (`-015`), and an approved repudiation every edge to {@code REPUDIATED} and the two
 * reopenings (`-023`). The generated {@code CHECK} and transition trigger mirror this enum,
 * reconciled by the migration test; an edge outside it is refused for every writer. `V003`
 * stated the machine whole but for two edges `V013` (`P8-TSK-023`) added —
 * {@code PARKED → UNMATCHED} (an over-paying bank item of another batch reopened whole, its
 * excess unparked) and {@code RESOLVED → REPUDIATED} (a resolved item of a repudiated batch) —
 * beside the reopening {@code MATCHED → UNMATCHED} (a bank item whose allocation named a
 * repudiated batch's remittance) it had stated from the start. *(Corrected 2026-10-01,
 * `P8-DOC-001`: this read "of which only birth is produced yet", and that `-023` would relax
 * nothing.)*
 */
public enum ItemStatus {

    /** Born so in the acceptance transaction; a run never completes over one (§5.3). */
    PENDING,

    /** Allocated whole under its pinned rule. Final but for its batch's repudiation. */
    MATCHED,

    /** A non-allocating fee line, checked against the pinned schedule. */
    CHECKED,

    /** A correction offset against its original's parked excess. */
    OFFSET,

    /** A remainder late internal evidence could still change; grace running. */
    UNMATCHED,

    /** The remainder fully parked under an owning break. */
    PARKED,

    /** A closing resolution took the parked value — terminal. */
    RESOLVED,

    /** Its batch was repudiated — terminal. */
    REPUDIATED;

    /** The states reachable from this one — `V003`'s trigger edges are generated from it. */
    public Set<ItemStatus> permittedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(MATCHED, CHECKED, OFFSET, UNMATCHED, PARKED);
            // The repudiation's reopening (a bank item of ANOTHER batch), and its own
            // batch's REPUDIATED - both -023's producers.
            case MATCHED -> EnumSet.of(UNMATCHED, REPUDIATED);
            case CHECKED, OFFSET -> EnumSet.of(REPUDIATED);
            case UNMATCHED -> EnumSet.of(MATCHED, PARKED, REPUDIATED);
            // PARKED -> UNMATCHED: an over-paying bank item of ANOTHER batch reopened whole,
            // its excess unparked, when the remittance it matched is repudiated (`V013`).
            case PARKED -> EnumSet.of(MATCHED, UNMATCHED, RESOLVED, REPUDIATED);
            // A resolved item of a repudiated batch leaves the live evidence too (`V013`):
            // RESOLVED is terminal but for its batch's repudiation.
            case RESOLVED -> EnumSet.of(REPUDIATED);
            case REPUDIATED -> EnumSet.noneOf(ItemStatus.class);
        };
    }

    /** The edges `V013` (`P8-TSK-023`) adds - absent from every earlier restatement. */
    static boolean addedByV013(ItemStatus from, ItemStatus to) {
        return (from == PARKED && to == UNMATCHED) || (from == RESOLVED && to == REPUDIATED);
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

    /** The transition trigger's edge condition as `V013` re-states it — the migration test's. */
    public static String sqlTransitionRule() {
        return sqlTransitionRule(false);
    }

    /** The edge condition every restatement before `V013` carried (`V003`, `V008`, `V009`). */
    public static String sqlTransitionRuleBeforeV013() {
        return sqlTransitionRule(true);
    }

    private static String sqlTransitionRule(boolean beforeV013) {
        return Arrays.stream(values())
                .filter(from -> edges(from, beforeV013).length() > 0)
                .map(
                        from ->
                                "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                                        + edges(from, beforeV013) + "))")
                .collect(Collectors.joining(" OR "));
    }

    private static String edges(ItemStatus from, boolean beforeV013) {
        return Arrays.stream(values())
                .filter(from.permittedTransitions()::contains)
                .filter(to -> !beforeV013 || !addedByV013(from, to))
                .map(to -> "'" + to.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
