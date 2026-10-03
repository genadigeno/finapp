package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * The payout a parked return names, locked, and whether its return was applied from settlement
 * evidence (the Phase 8 -> 9 transition, IDEM-1) - declared here, implemented in {@code app} over
 * merchant's {@code PayoutReturns} (ADR-0064: reconciliation compiles against no sibling but
 * {@code ledger}).
 *
 * <p>A person's {@code TRANSFER_TO_ACCOUNT} out of a {@code RETURN_NOT_APPLICABLE} break is the
 * payout return's fallback (ADR-0073 §5). It and the return worker must never both credit the
 * merchant for one return, so both serialise on ONE row: the payout's, {@code FOR UPDATE} - the
 * row {@code PayoutReturns.apply} already takes first. The proposal and the approval take it
 * after their own break, resolution and suspense rows and before the transfer's target; the
 * worker takes it after its item's share lock and before the payable's. Neither side holds what
 * the other waits for, so the two orders cannot deadlock.
 */
@FunctionalInterface
public interface ReturnedPayouts {

    /** Nothing names a payout: the composition's answer outside a merchant-aware deployment. */
    ReturnedPayouts NONE = (unitOfWork, providerReference, ourReference) -> Optional.empty();

    /**
     * The payout locked, as the return worker finds it.
     *
     * @param payoutOperationRef the payout's identifier as the lookup freezes it on a break
     *     ({@code internal_operation_ref})
     * @param returnApplied whether a {@code payout_return} stands for it
     * @param payoutState the payout's status under the lock, classified as the typing lookup
     *     classifies it: only a {@code COMPLETED} payout debited the merchant's payable, so only
     *     then can a person's transfer of its return re-credit the merchant once (the Phase 8 -> 9
     *     transition, IDEM-1's residual)
     */
    record LockedPayout(
            String payoutOperationRef, boolean returnApplied, InternalClassification payoutState) {

        public LockedPayout {
            Objects.requireNonNull(payoutOperationRef, "payoutOperationRef must not be null");
            Objects.requireNonNull(payoutState, "payoutState must not be null");
        }
    }

    /**
     * The payout the references name - the provider's first, then ours, exactly as the worker
     * finds it - locked {@code FOR UPDATE} for the rest of the transaction; empty when none does.
     */
    Optional<LockedPayout> lock(
            Connection unitOfWork, Optional<String> providerReference, Optional<String> ourReference);
}
