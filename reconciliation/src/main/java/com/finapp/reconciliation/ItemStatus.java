package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The external item's matching status (`P8-TSK-009`,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.4) — of which only birth is produced yet.
 *
 * <p>`P8-TSK-009` births {@code PENDING} rows, one per accepted settlement line, and drives
 * no edge; the run, rematch and grace legs arrive with `-011`/`-013`, the resolutions with
 * `-015`, the repudiation's edges with `-023`. The whole machine is stated NOW (the `V002`
 * expectation precedent): the generated {@code CHECK} and transition trigger mirror this
 * enum, reconciled by the migration test, and the narrowed {@code UPDATE} plus the absent
 * producers keep the unproduced edges inert. The one deliberate reopening —
 * {@code MATCHED → UNMATCHED}, a bank item whose allocation named a repudiated batch's
 * remittance — is stated in the machine so `-023` relaxes nothing.
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
            // batch's REPUDIATED - both -023's producers, stated now.
            case MATCHED -> EnumSet.of(UNMATCHED, REPUDIATED);
            case CHECKED, OFFSET -> EnumSet.of(REPUDIATED);
            case UNMATCHED -> EnumSet.of(MATCHED, PARKED, REPUDIATED);
            case PARKED -> EnumSet.of(MATCHED, RESOLVED, REPUDIATED);
            case RESOLVED, REPUDIATED -> EnumSet.noneOf(ItemStatus.class);
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
