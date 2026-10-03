package com.finapp.reconciliation;

import java.sql.Connection;

/**
 * Reconciliation's public read of a payout return's person fallback (the Phase 8 -> 9
 * transition, IDEM-1; ADR-0073 §5): whether a person's {@code TRANSFER_TO_ACCOUNT} out of a
 * {@code RETURN_NOT_APPLICABLE} break naming the payout stands {@code PROPOSED} or
 * {@code APPROVED}. The return worker in {@code app} asks it under the payout's row lock and,
 * when it does, writes nothing: the person's transfer IS the payout's return, and a later
 * report's repeat of the line must never credit the merchant a second time. Never SQL across
 * schemas - this is the seam.
 */
@FunctionalInterface
public interface PayoutReturnFallbacks {

    /**
     * Lock-free - read under the payout row's lock, which every proposal and approval of such a
     * transfer takes before it decides.
     *
     * @param payoutOperationRef the payout's identifier, as the lookup freezes it on a break
     */
    boolean transferStands(Connection unitOfWork, String payoutOperationRef);
}
