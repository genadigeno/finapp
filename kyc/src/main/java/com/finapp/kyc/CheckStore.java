package com.finapp.kyc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores verification checks and their evidence (`P2-TSK-009`).
 *
 * <p>Every write here is either a claim-by-insert behind a savepoint or a conditional
 * {@code UPDATE} whose row count is the outcome — the concurrency protocol is the statement, so
 * ten instances running one case's checks produce one check per type, one provider call per
 * check and one outcome per check, with the database arbitrating (ADR-0014's normal case).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CheckStore<T> {

    /**
     * Requests a check, or converges on the in-flight or already-answered identical one.
     *
     * <p>The one-in-flight partial unique index on {@code (case_id, check_type)} is the arbiter;
     * a lost race is handed the winner's check ({@code openOrConverge}'s semantics). Convergence
     * finds the newest check of the type: an in-flight or answered ({@code CLEAR}/{@code HIT})
     * one is converged on — a second run must not re-ask an answered question — while a terminal
     * {@code INDETERMINATE} under the retry budget invites a <em>new</em> check, and one at the
     * budget is converged on because the assessment routes the type to a person (`P2-TSK-010`,
     * correcting this doc's earlier "any state": an unknown is not an answer, ADR-0038).
     *
     * <p>An {@link CheckPurpose#ONBOARDING} check, always: since `P13-TSK-022` a rescreen of the
     * case is neither converged on, counted against the budget nor held by the one-in-flight
     * index ({@code kyc V011}) - a list update's question is not the run's.
     */
    Requested requestOrConverge(T unitOfWork, VerificationCheck fresh);

    /**
     * One check by its identifier.
     *
     * <p>Added for the callback door (`P2-TSK-011`), whose identifier arrives from an external
     * caller — which is why {@code OwnershipIsScopedTest} classifies the implementation rather
     * than waving it through: the signature at the boundary is what authorises the naming.
     */
    Optional<VerificationCheck> findById(T unitOfWork, CheckId checkId);

    /**
     * {@code REQUESTED → DISPATCHED}, conditionally.
     *
     * @return whether this caller won the dispatch — and with it, the right to call the
     *     provider. A false is a concurrent dispatcher, and the loser must not call: one
     *     dispatch, one provider effect.
     */
    boolean dispatch(T unitOfWork, CheckId checkId, Instant at);

    /**
     * {@code DISPATCHED → } the outcome's terminal state, conditionally.
     *
     * @return whether this caller recorded the outcome. A false is a duplicate completion, and
     *     the loser writes no evidence and no audit record — one outcome, one record set.
     */
    boolean complete(T unitOfWork, CheckId checkId, CheckOutcome outcome, Instant at);

    /**
     * The case's <strong>onboarding</strong> checks, evidence excluded - what a <em>decided</em>
     * case's re-assessment reads (a duplicate callback's healing), as it always has.
     *
     * <p>`P13-TSK-022` ({@code kyc V011}): a {@link CheckPurpose#RESCREEN} check is not one of
     * them - on a decided case a list update's screening is evidence beside the decision, never a
     * re-decision, so a decided case's re-assessment never opens a review task for it.
     */
    List<VerificationCheck> forCase(T unitOfWork, KycCaseId caseId);

    /**
     * Every check of a case, onboarding and rescreen alike, evidence excluded (`P13-TSK-022` and
     * its review's fail-closed rule): what an <em>undecided</em> case's assessment and decision
     * read - a rescreen's {@code HIT} before the decision blocks the automatic approval and needs a
     * person ({@link ChecksAssessment#of}), and the decision's evidence names every answered
     * rescreen ({@link ChecksAssessment#decisionEvidence}) - and what the reviewer reads, decided
     * or not, so a rescreen beside the decision is visible to the person defending it.
     */
    List<VerificationCheck> allForCase(T unitOfWork, KycCaseId caseId);

    /**
     * Per screening type - {@code SANCTIONS}, {@code PEP} - the case's decided check
     * ({@code CLEAR} or {@code HIT}) asked last, onboarding or rescreen alike: at most one row per
     * type (`P13-TSK-022`'s review). Risk's screening-hit reading on T-eval's hot path reads this -
     * one index probe per type on {@code kyc V011}'s {@code verification_check_decided_latest},
     * never every check of a case, whose rescreens accumulate with every list update.
     */
    List<VerificationCheck> latestDecidedScreenings(T unitOfWork, KycCaseId caseId);

    /**
     * Requests a {@link CheckPurpose#RESCREEN} screening of {@code type} on {@code caseId} under
     * {@code rescreenKey}, or converges on the check that key already asked (`P13-TSK-022`, the
     * owner's decision O1; {@code kyc V011}).
     *
     * <p><strong>Keyed, never converged on an answer of another question.</strong> The key -
     * risk's permit key and its retry ordinal - is unique ({@code
     * verification_check_rescreen_once}): a second ask under it, from any instance, a lease lost
     * mid-call or a replay, lands on the same row, so one key is one question and one provider
     * effect (the dispatch below is still conditional). It never converges on an answered
     * onboarding check (that is the point: the list changed), and onboarding's one-in-flight
     * index does not hold it - a rescreen and an onboarding run are two questions. The ask's
     * instant is the <strong>database's</strong> ({@code clock_timestamp()}), the clock the
     * list version's activation is stamped on, so "asked after the activation" is one clock.
     *
     * @param fresh the identifier a created row takes - ignored when the key already asked
     * @throws IllegalArgumentException for a type that is not a screening
     * @throws IllegalStateException when the key already asked another case or another type
     */
    Requested requestRescreen(
            T unitOfWork, KycCaseId caseId, CheckType type, CheckId fresh, String rescreenKey);

    /** The check {@code rescreenKey} asked, if it asked one (`P13-TSK-022`). */
    Optional<VerificationCheck> findRescreen(T unitOfWork, String rescreenKey);

    /**
     * Appends the raw bytes a provider answered with, verbatim ({@code INV-HIST-02}).
     *
     * <p>Encrypted at rest and checksummed by the adapter underneath — the
     * {@code kyc_document} treatment, because provider evidence about a person is
     * {@code RESTRICTED-PII} at its ceiling exactly as a document is (ADR-0036 groups the two
     * under one at-rest decision).
     */
    void appendEvidence(
            T unitOfWork, EvidenceId id, CheckId checkId, byte[] payload, Instant receivedAt);

    /** The outcome of a request: the check that now exists, and whether this call created it. */
    record Requested(VerificationCheck check, boolean created) {
        public Requested {
            Objects.requireNonNull(check, "check must not be null");
        }
    }
}
