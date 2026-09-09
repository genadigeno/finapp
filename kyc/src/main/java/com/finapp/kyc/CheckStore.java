package com.finapp.kyc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
     * looks for an existing check of the type in <em>any</em> state, newest first — a second run
     * over a case with a {@code CLEAR} check must find that check, not insert a duplicate
     * question.
     */
    Requested requestOrConverge(T unitOfWork, VerificationCheck fresh);

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

    /** Every check of a case, evidence excluded. */
    List<VerificationCheck> forCase(T unitOfWork, KycCaseId caseId);

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
