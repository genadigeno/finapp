package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The settlement expectation's machine (`P8-TSK-004`,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.5) — of which only birth is produced yet.
 *
 * <p>`P8-TSK-004` writes {@code OPEN} rows and nothing else; allocation drives the settling
 * edges (`P8-TSK-011`), an approved closing resolution drives {@code RESOLVED_BY_ADJUSTMENT}
 * (`P8-TSK-015`), and a repudiation's counter-allocations reopen (`P8-TSK-023`). The whole
 * machine is stated NOW — unlike the settlement file's, whose edges arrive constraint by
 * constraint — because `V002`'s generated {@code CHECK} and transition trigger are this
 * enum's mirror, reconciled by the migration test, and the narrowed {@code UPDATE} grant
 * plus the frozen birth columns mean a premature writer can move a status and nothing else:
 * an edge with no producer is inert, an edge outside the machine is refused for every writer.
 *
 * <p>{@code overdue_since} is deliberately NOT a state: a one-way {@code NULL → value} fact
 * (`P8-TSK-013`), because an overdue expectation still settles (`INV-SET-03`).
 */
public enum ExpectationStatus {

    /** Born so, with nothing allocated ({@code allocated_minor = resolved_minor = 0}). */
    OPEN,

    /** Some but not all of the amount is allocated. */
    PARTIALLY_SETTLED,

    /**
     * The allocations equal the amount. Final but for a repudiation's reopening —
     * {@code SettlementExpectationSettled} names that terminal-unless-repudiated fact.
     */
    SETTLED,

    /**
     * An approved closing resolution took the remainder into {@code resolved_minor}
     * (`WRITE_OFF`, `TRANSFER_TO_ACCOUNT`; ADR-0071). Terminal: no edge leaves it.
     */
    RESOLVED_BY_ADJUSTMENT;

    /** The states reachable from this one — the trigger's edge conditions are generated from it. */
    public Set<ExpectationStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(PARTIALLY_SETTLED, SETTLED, RESOLVED_BY_ADJUSTMENT);
            // The repudiation's counter-allocations restore the remainder (INV-REV-01's
            // shape applied to matches): both settled states can walk back.
            case PARTIALLY_SETTLED ->
                    EnumSet.of(SETTLED, RESOLVED_BY_ADJUSTMENT, OPEN);
            case SETTLED -> EnumSet.of(OPEN, PARTIALLY_SETTLED);
            case RESOLVED_BY_ADJUSTMENT -> EnumSet.noneOf(ExpectationStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The `V002` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
