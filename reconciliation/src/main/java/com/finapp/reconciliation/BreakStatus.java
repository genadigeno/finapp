package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The break's machine (`P8-TSK-010`, ADR-0069 §6,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.6) — of which only birth is produced yet.
 *
 * <p>`P8-TSK-010` raises {@code OPEN} rows and nothing else; assignment drives
 * {@code INVESTIGATING} (`P8-TSK-014`), proposals and their outcomes drive
 * {@code RESOLUTION_PROPOSED} and back (`P8-TSK-015`), and {@code RESOLVED} is reached by an
 * approval, by the platform's {@code EVIDENCED} (`P8-TSK-012`) or by a timing difference's
 * zero-value {@code ACKNOWLEDGE} (one person's, `P8-TST-002`). The whole machine is stated NOW — the `V002` expectation precedent:
 * the generated {@code CHECK} and every-writer transition trigger are this enum's mirror,
 * reconciled by the migration test, and the unproduced edges stay inert behind the narrowed
 * {@code UPDATE} grant and the absent producers.
 */
public enum BreakStatus {

    /** Born so, from a stored fact the platform detected — never by a person. */
    OPEN,

    /** The first assignment moved it here; the case file hangs off the break. */
    INVESTIGATING,

    /** Exactly one live resolution proposal stands (`P8-TSK-015`). */
    RESOLUTION_PROPOSED,

    /**
     * Terminal: an approved resolution, the platform's {@code EVIDENCED}, or a timing
     * difference's one-person zero-value {@code ACKNOWLEDGE}. A recurrence is a NEW break naming its predecessor
     * ({@code follows_break_id}) — never a reopening.
     */
    RESOLVED;

    /** The states reachable from this one — `V004`'s trigger edges are generated from it. */
    public Set<BreakStatus> permittedTransitions() {
        return switch (this) {
            // OPEN -> RESOLVED is EVIDENCED's and the timing difference's one-person zero-value
            // ACKNOWLEDGE's own edge.
            case OPEN -> EnumSet.of(INVESTIGATING, RESOLUTION_PROPOSED, RESOLVED);
            case INVESTIGATING -> EnumSet.of(RESOLUTION_PROPOSED, RESOLVED);
            // Back to INVESTIGATING on a rejection or a withdrawal.
            case RESOLUTION_PROPOSED -> EnumSet.of(INVESTIGATING, RESOLVED);
            case RESOLVED -> EnumSet.noneOf(BreakStatus.class);
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
