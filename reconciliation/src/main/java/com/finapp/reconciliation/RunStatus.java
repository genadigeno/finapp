package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The reconciliation run's machine (`P8-TSK-009`,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.3) — of which only birth is produced yet.
 *
 * <p>`P8-TSK-009` births {@code OPEN} rows (kind {@code BATCH}) in the acceptance
 * transaction and nothing else; the run leg drives the working edges (`P8-TSK-011`), the
 * sweep's run-block detection and the requeue arrive with `-013`/`-014`. The whole machine
 * is stated NOW — the expectation register's `V002` precedent: the generated {@code CHECK}
 * and transition trigger are this enum's mirror, reconciled by the migration test, and the
 * narrowed {@code UPDATE} plus the absent producers keep the unproduced edges inert.
 */
public enum RunStatus {

    /** Born so by the acceptance (kind {@code BATCH}) or a keyed reprocess (`P8-TSK-014`). */
    OPEN,

    /** A chunk is walking the items (`P8-TSK-011`); the cursor advances per chunk. */
    IN_PROGRESS,

    /** No item {@code PENDING} — terminal. */
    COMPLETED,

    /** N consecutive chunk failures: the source held visibly until a person requeues. */
    BLOCKED;

    /** The states reachable from this one — `V003`'s trigger edges are generated from it. */
    public Set<RunStatus> permittedTransitions() {
        return switch (this) {
            // OPEN -> COMPLETED is the empty run's own edge (item_count = 0).
            case OPEN -> EnumSet.of(IN_PROGRESS, COMPLETED, BLOCKED);
            case IN_PROGRESS -> EnumSet.of(COMPLETED, BLOCKED);
            // The requeue (a person, reasoned): back to work, never silently skipped.
            case BLOCKED -> EnumSet.of(IN_PROGRESS);
            case COMPLETED -> EnumSet.noneOf(RunStatus.class);
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
