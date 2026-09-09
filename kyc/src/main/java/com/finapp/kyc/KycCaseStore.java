package com.finapp.kyc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for KYC cases (`P2-TSK-005`).
 *
 * <p>A port, on ADR-0033's recorded reasoning: the aggregate must not depend on a {@code Jdbc}
 * class, and the unit of work is the caller's — every write joins the transaction of the
 * operation performing it, so an audit record or an outbox row written beside it commits or
 * rolls back with it.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface KycCaseStore<T> {

    /**
     * The result of asking for a customer's case to exist: the case, and whether this call
     * created it.
     *
     * <p>Both callers-to-come need the distinction — {@code POST /v1/me/kyc} answers
     * "created" versus "you already had one", and the registration consumer counts effects —
     * while neither treats convergence as an error, which is the point.
     */
    record Opening(KycCase kycCase, boolean created) {}

    /**
     * Inserts {@code fresh}, or converges on the customer's existing open case.
     *
     * <p><strong>The one-open-case index is the arbiter</strong> ({@code
     * kyc_case_one_open_per_customer}): a rule across aggregates of the same type, which only
     * the database can enforce between two concurrent transactions ({@code P1-TSK-005}'s
     * reasoning, verbatim). Ten instances opening for one customer produce one row; the nine
     * losers are handed the winner's case rather than an error, because "ensure my case exists"
     * is the semantics every caller actually wants ({@code P2-TSK-006}'s converge-don't-race).
     *
     * <p>Behind a savepoint, because a unique violation aborts the transaction and the caller's
     * other writes must survive the lost race — and a pre-flight {@code SELECT} is not a
     * substitute: two instances would both see no case, both insert, and one would get the
     * violation anyway ({@code P1-TSK-006}'s recorded reasoning).
     */
    Opening openOrConverge(T unitOfWork, KycCase fresh);

    /** The customer's open (non-terminal) case, if any. The predicate is the index's own. */
    Optional<KycCase> findOpenFor(T unitOfWork, UUID customerId);

    /**
     * Moves a case's status, conditionally: the row moves only if it still holds {@code from},
     * and the row count is the outcome.
     *
     * <p>The platform's transition idiom ({@code INV-KYC-03}'s named mechanism): two instances
     * racing one transition produce one {@code true} and one {@code false}, decided by the
     * database rather than by anything either instance read earlier. The caller decides what a
     * lost race means — for a duplicate-driven transition it is usually "already done, fine".
     * The aggregate's own {@code canTransitionTo} remains the machine's authority
     * ({@code INV-LIFE-02}); this is the write that makes one instance's application of it
     * stick.
     */
    boolean moveStatus(
            T unitOfWork, KycCaseId caseId, KycCaseStatus from, KycCaseStatus to, Instant at);
}
