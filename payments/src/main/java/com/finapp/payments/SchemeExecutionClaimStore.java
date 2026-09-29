package com.finapp.payments;

/**
 * Persistence for {@link SchemeExecutionClaim} (the Phase 7 -&gt; 8 transition, over
 * {@code V023}) — append-only, its primary key the arbiter.
 */
public interface SchemeExecutionClaimStore<T> {

    /**
     * Claims the execution for {@code claim}'s subject, or yields to the claim that stands.
     *
     * <p>Returns the claim that stands <em>after</em> the attempt: this one when it landed —
     * or when the same subject had claimed it before (a replay converges) — and otherwise the
     * earlier claimant's. A concurrent claimant of the same execution blocks on the
     * in-progress row until its transaction ends, so the answer is always a committed fact or
     * this transaction's own; callers compare with {@link SchemeExecutionClaim#heldBy}.
     */
    SchemeExecutionClaim claim(T unitOfWork, SchemeExecutionClaim claim);

    /**
     * The claim standing for {@code (rail, schemeReference)}, if any — the reconciliation
     * lookup's read half (`P8-TSK-010`, ADR-0069 §2): a claim is taken only when an
     * execution completed, so a reference no claim holds names no completed execution.
     * Lock-free.
     */
    java.util.Optional<SchemeExecutionClaim> findByExecution(
            T unitOfWork, RailId rail, ProviderReference schemeReference);
}
