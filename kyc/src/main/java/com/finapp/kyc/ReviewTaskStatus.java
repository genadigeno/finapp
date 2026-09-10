package com.finapp.kyc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a review task (`PHASE_2_PLAN.md` §5, `P2-TSK-010`, {@code INV-LIFE-01}).
 *
 * <pre>
 * OPEN → RESOLVED
 * </pre>
 *
 * <p><strong>{@code RESOLVED} is terminal, and there is no unresolve</strong>
 * ({@code INV-LIFE-04}): a resolution is a recorded judgement about what one check raised, and a
 * wrong one is corrected by a <em>new review event on the case</em> — the plan's own words —
 * never by reopening the record that a decision may later rest on ({@code INV-KYC-02}'s
 * defensibility). Changed circumstances are a new <em>check</em>, which brings its own task.
 *
 * <p>The resolution's payload — who, why, with what verdict — is `P2-TSK-012`'s design to shape;
 * this machine is declared whole because the plan states it outright (the deliberately-few
 * licence's boundary), and the {@code CHECK} constraint in {@code V005} is generated from
 * {@link #sqlValueList()} so the schema and this enum cannot drift
 * ({@code ReviewTaskMigrationTest}, the {@code P0-TSK-022} pattern).
 */
public enum ReviewTaskStatus {

    /** A person owes this check a judgement. The only state `P2-TSK-010` ever writes. */
    OPEN,

    /** Terminal. Written by `P2-TSK-012`'s resolution, with its reason and reviewer. */
    RESOLVED;

    /** The states reachable from this one — the {@code KycCaseStatus} idiom. */
    public Set<ReviewTaskStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(RESOLVED);
            case RESOLVED -> EnumSet.noneOf(ReviewTaskStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(ReviewTaskStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
