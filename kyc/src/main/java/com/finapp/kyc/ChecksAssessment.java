package com.finapp.kyc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The platform's own reading of a case's checks (`P2-TSK-009`, `P2-TSK-010`,
 * {@code INV-KYC-01}).
 *
 * <p>This rule is what stands between a provider's answer and the case's status: no adapter and
 * no runner maps a verdict onto the case — they record a <em>check</em> outcome, and the case
 * moves only by what this assessment says about the whole.
 *
 * <h2>The three answers, and the order they are decided in</h2>
 *
 * <ol>
 *   <li><strong>{@code BLOCKED}: something needs a person.</strong> Any {@code HIT}, anywhere —
 *       decided first, because it must win every tie ({@code INV-KYC-04}): not even a later
 *       {@code CLEAR} of the same type un-blocks it, because a hit is resolved by a
 *       <em>person</em>, never by a retry. And, since `P2-TSK-010`, a case whose questions are
 *       all settled but where a required type has gone {@code INDETERMINATE} past its retry
 *       budget: after {@link #INDETERMINATE_RETRY_BUDGET} unknowns the platform stops asking
 *       machines and asks a person — silence resolves nothing, in either direction.
 *   <li>{@code INCOMPLETE}: anything in flight, or a required type still answerable — no
 *       {@code CLEAR} yet and budget remaining. An {@code INDETERMINATE} followed by a
 *       {@code CLEAR} of the same type does not block — resolution-is-a-new-check (ADR-0038)
 *       made real — and one still under budget leaves the type unanswered, which is
 *       {@code INCOMPLETE}, never a pass and never a refusal ({@code INV-LIFE-03}).
 *   <li>{@code CLEAR_TO_PROCEED}: every required type has a {@code CLEAR}, nothing in flight,
 *       no hit.
 * </ol>
 *
 * <h2>The deliberate asymmetry ({@code INV-KYC-04} both ways)</h2>
 *
 * <p>A {@code CLEAR} of a type never un-blocks that type's {@code HIT}; a later {@code CLEAR}
 * <em>does</em> satisfy a type whose budget is exhausted. A hit is an answer that demands a
 * person; exhaustion is the absence of an answer, and an answer arriving ends the absence.
 */
public enum ChecksAssessment {
    CLEAR_TO_PROCEED,
    BLOCKED,
    INCOMPLETE;

    /**
     * How many {@code INDETERMINATE} answers of one type a case tolerates before the question
     * goes to a person instead of back to the machine.
     *
     * <p>Part of what the platform makes of a case's checks, so it lives here; the versioned
     * policy artefact that would carry it is `P2-TSK-013`'s world, and the case's pinned
     * {@code policy_version} names the regime this constant belonged to. The convergence rule
     * in {@code JdbcCheckStore.requestOrConverge} compares with {@code >=} on the count, so the
     * recorded redundant-question race overshooting by one routes to review <em>sooner</em> —
     * the safe direction — never later.
     */
    public static final int INDETERMINATE_RETRY_BUDGET = 3;

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

        if (checks.stream().anyMatch(check -> check.status() == CheckStatus.HIT)) {
            return BLOCKED;
        }
        if (checks.stream().anyMatch(check -> !check.status().isTerminal())) {
            return INCOMPLETE;
        }

        boolean anyExhausted = false;
        for (CheckType required : requiredTypes) {
            if (hasClear(checks, required)) {
                continue;
            }
            if (isExhausted(checks, required)) {
                anyExhausted = true;
                continue;
            }
            // No CLEAR and budget remaining: still answerable by a retry, so unanswered.
            return INCOMPLETE;
        }
        return anyExhausted ? BLOCKED : CLEAR_TO_PROCEED;
    }

    /**
     * The checks a {@code BLOCKED} case owes a person: every {@code HIT}, and the newest
     * {@code INDETERMINATE} of each budget-exhausted required type.
     *
     * <p>Empty exactly when {@link #of} is not {@code BLOCKED} — the equivalence
     * {@code ChecksAssessmentTest} pins, so the enum and the task-set derivation cannot
     * disagree about whether a case needs review. The newest of an exhausted type, because that
     * row is the freshest evidence of the provider's silence and the one the reviewer starts
     * from; its predecessors stay true beside it (ADR-0038).
     */
    public static List<VerificationCheck> needingReview(
            Set<CheckType> requiredTypes, Collection<VerificationCheck> checks) {
        if (of(requiredTypes, checks) != BLOCKED) {
            return List.of();
        }
        List<VerificationCheck> needing = new ArrayList<>();
        for (VerificationCheck check : checks) {
            if (check.status() == CheckStatus.HIT) {
                needing.add(check);
            }
        }
        for (CheckType required : requiredTypes) {
            if (hasClear(checks, required) || !isExhausted(checks, required)) {
                continue;
            }
            checks.stream()
                    .filter(check -> check.type() == required)
                    .filter(check -> check.status() == CheckStatus.INDETERMINATE)
                    .max(NEWEST)
                    .ifPresent(needing::add);
        }
        return List.copyOf(needing);
    }

    /** The store's {@code ORDER BY requested_at DESC, id DESC}, in memory. */
    private static final Comparator<VerificationCheck> NEWEST =
            Comparator.comparing(VerificationCheck::requestedAt)
                    .thenComparing(check -> check.id().value().toString());

    private static boolean hasClear(Collection<VerificationCheck> checks, CheckType type) {
        return checks.stream()
                .anyMatch(check -> check.type() == type && check.status() == CheckStatus.CLEAR);
    }

    /**
     * A type nothing has answered and nothing will re-ask: no {@code CLEAR}, nothing in flight,
     * and at least {@link #INDETERMINATE_RETRY_BUDGET} unknowns.
     */
    private static boolean isExhausted(Collection<VerificationCheck> checks, CheckType type) {
        boolean inFlight =
                checks.stream()
                        .anyMatch(check -> check.type() == type && !check.status().isTerminal());
        if (inFlight) {
            return false;
        }
        long unknowns =
                checks.stream()
                        .filter(check -> check.type() == type)
                        .filter(check -> check.status() == CheckStatus.INDETERMINATE)
                        .count();
        return unknowns >= INDETERMINATE_RETRY_BUDGET;
    }
}
