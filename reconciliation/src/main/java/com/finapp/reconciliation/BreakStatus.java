package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The break's machine (`P8-TSK-010`, ADR-0069 §6,
 * `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.6) — every edge of which now has its
 * producer.
 *
 * <p>`P8-TSK-010` raises {@code OPEN} rows; assignment drives {@code INVESTIGATING}
 * (`P8-TSK-014`), proposals and their outcomes drive {@code RESOLUTION_PROPOSED} and back
 * (`P8-TSK-015`), and {@code RESOLVED} is reached by an approval, by the platform's
 * {@code EVIDENCED} (`P8-TSK-012`), by the one-person zero-value {@code ACKNOWLEDGE} of a
 * {@code TIMING_DIFFERENCE} raised by a timing detector (its cause {@code LATE_MATCH} or
 * {@code CYCLE_MISMATCH}; reconciliation `V014`, `P8-TST-002`), or by an approved
 * {@code REPUDIATE_BATCH} closing a break the repudiation emptied, recorded in
 * {@code repudiation_closure} (`P8-TSK-023`). The whole machine was stated by `V004` — the
 * `V002` expectation precedent: the generated {@code CHECK} and every-writer transition trigger
 * are this enum's mirror, reconciled by the migration test. Reconciliation `V015`
 * (`P8-DOC-001`) adds a deferred constraint trigger: a break becomes {@code RESOLVED} only if,
 * at commit, a {@code RESOLVED} {@code break_event} of it names, by {@code resolution_id}, a
 * resolution that is {@code APPROVED}, for every writer. That the named resolution is the
 * break's own is held by the domain, not the trigger (recorded debt, Phase 15). *(Corrected 2026-10-01, `P8-DOC-001`: this read "of which only birth is
 * produced yet", named the one-person act a timing difference's without its cause, and omitted
 * the repudiation's closure.)*
 */
public enum BreakStatus {

    /** Born so, from a stored fact the platform detected — never by a person. */
    OPEN,

    /** The first assignment moved it here; the case file hangs off the break. */
    INVESTIGATING,

    /** Exactly one live resolution proposal stands (`P8-TSK-015`). */
    RESOLUTION_PROPOSED,

    /**
     * Terminal: an approved resolution, the platform's {@code EVIDENCED}, the one-person
     * zero-value {@code ACKNOWLEDGE} of a timing detector's {@code TIMING_DIFFERENCE}, or an
     * approved repudiation's closure. A recurrence is a NEW break naming its predecessor
     * ({@code follows_break_id}) — never a reopening.
     */
    RESOLVED;

    /** The states reachable from this one — `V004`'s trigger edges are generated from it. */
    public Set<BreakStatus> permittedTransitions() {
        return switch (this) {
            // OPEN -> RESOLVED is EVIDENCED's, the timing detector's one-person zero-value
            // ACKNOWLEDGE's and a repudiation closure's edge.
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
