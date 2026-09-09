package com.finapp.kyc;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The platform's own reading of a case's checks (`P2-TSK-009`, {@code INV-KYC-01}).
 *
 * <p>This rule is what stands between a provider's answer and the case's status: no adapter and
 * no runner maps a verdict onto the case — they record a <em>check</em> outcome, and the case
 * moves only by what this assessment says about the whole.
 *
 * <h2>The three answers, and the order they are decided in</h2>
 *
 * <ol>
 *   <li><strong>{@code BLOCKED}: any {@code HIT}, anywhere.</strong> Decided first, because it
 *       must win every tie ({@code INV-KYC-04}): a case with a hit must never proceed silently,
 *       whatever else its checks say — not even a later {@code CLEAR} of the same type
 *       un-blocks it, because a hit is resolved by a <em>person</em>, never by a retry.
 *   <li>{@code INCOMPLETE}: anything in flight, or a required type with no {@code CLEAR} yet. An
 *       {@code INDETERMINATE} followed by a {@code CLEAR} of the same type does not block —
 *       resolution-is-a-new-check (ADR-0038) made real — and an {@code INDETERMINATE} with no
 *       successor leaves the type unanswered, which is {@code INCOMPLETE}, never a pass and
 *       never a refusal ({@code INV-LIFE-03}).
 *   <li>{@code CLEAR_TO_PROCEED}: every required type has a {@code CLEAR}, nothing in flight,
 *       no hit.
 * </ol>
 *
 * <p>The routing of {@code BLOCKED} to {@code IN_REVIEW} — with the review tasks that give that
 * state an exit — is `P2-TSK-010`'s; here a blocked case simply does not proceed.
 */
public enum ChecksAssessment {
    CLEAR_TO_PROCEED,
    BLOCKED,
    INCOMPLETE;

    /**
     * Assesses a case's checks against the types a run requires.
     *
     * @param requiredTypes the types this platform's run demands — the registered providers'
     *     set, never derived from what happens to exist in the table, because an empty table
     *     assessing as clear-to-proceed would decide a case by absence of evidence
     */
    public static ChecksAssessment of(
            Set<CheckType> requiredTypes, Collection<VerificationCheck> checks) {
        Objects.requireNonNull(requiredTypes, "requiredTypes must not be null");
        Objects.requireNonNull(checks, "checks must not be null");
        if (requiredTypes.isEmpty()) {
            // No requirement can never mean "proceed": it means nobody has said what a run is.
            throw new IllegalArgumentException("a verification run requires at least one type");
        }

        boolean anyHit = checks.stream().anyMatch(check -> check.status() == CheckStatus.HIT);
        if (anyHit) {
            return BLOCKED;
        }

        boolean anyInFlight = checks.stream().anyMatch(check -> !check.status().isTerminal());
        Set<CheckType> cleared = EnumSet.noneOf(CheckType.class);
        for (VerificationCheck check : checks) {
            if (check.status() == CheckStatus.CLEAR) {
                cleared.add(check.type());
            }
        }
        if (anyInFlight || !cleared.containsAll(requiredTypes)) {
            return INCOMPLETE;
        }
        return CLEAR_TO_PROCEED;
    }
}
