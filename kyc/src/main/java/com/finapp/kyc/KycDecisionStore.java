package com.finapp.kyc;

/**
 * Stores KYC/KYB decisions (`P2-TSK-013`, {@code INV-KYC-02}).
 *
 * <p>Append-only at {@code DB-PRIVILEGE}: the application role holds {@code SELECT, INSERT} and
 * nothing else on both decision tables, so an edit is not a forbidden code path — it is a
 * statement the database refuses whoever issues it (the {@code audit_record} model).
 *
 * <p><strong>The store is not the concurrency arbiter, and that is deliberate.</strong> The
 * recording service wins the conditional case move ({@code READY_FOR_DECISION → } terminal)
 * first, in the same transaction, and only the winner inserts — so by construction at most one
 * decision reaches this insert per case. The total {@code UNIQUE (case_id)} index underneath is
 * defence in depth: if it ever fires, an invariant has already been violated upstream, which is
 * why the failure is loud rather than a converge.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface KycDecisionStore<T> {

    /**
     * Records a decision and the evidence references it rested on, atomically with whatever
     * else rides the caller's transaction.
     */
    void record(T unitOfWork, KycDecision decision);
}
