package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the merchant refund (`P12-TSK-021`, {@code bnpl V012}): the purchase's row lock (B5) - merchant-scoped in
 * the statement at the merchant's door - the refund born under a requester's key (the arbiter behind a lapsed claim), the
 * purchase's committed refunds and the obligation's adjustments the refund's position is derived from, the schedule and
 * agreement versions a refund makes, the refund and its adjustments, the purchase's terminal edge, and the merchant's own
 * reads of its refunds. Every instant the database's; every read a merchant reaches carries {@code merchant_id = ?} in its
 * statement.
 */
public interface BnplRefundStore {

    /**
     * (B5) {@code merchant}'s purchase {@code id} {@code FOR UPDATE} with its financing and obligation -
     * {@code merchant_id = ?} in the locking statement, so another merchant's purchase is never locked and is the same
     * empty answer as an absent one ({@code INV-MER-01}).
     */
    Optional<LockedPurchase> lockAtMerchant(Connection unitOfWork, BnplPurchaseId id, UUID merchant);

    /** (B5) the purchase {@code id} {@code FOR UPDATE} with its financing and obligation - the operator's door. */
    Optional<LockedPurchase> lock(Connection unitOfWork, BnplPurchaseId id);

    /** The refund born at {@code merchant} under {@code requestedBy}'s {@code refundKey} - the unique's arbiter. */
    Optional<BnplRefund> findByKey(Connection unitOfWork, UUID merchant, String requestedBy, String refundKey);

    /** The purchase's committed refunds, oldest first - read under B5, so none is in flight beside them. */
    List<BnplRefund> ofPurchase(Connection unitOfWork, BnplPurchaseId purchase);

    /** Every refund adjustment of the obligation - each instalment's cancellations, read under B4. */
    List<BnplRefund.Adjustment> adjustmentsOf(Connection unitOfWork, BnplObligationId obligation);

    /** Births a refund's schedule version n+1 and its instalments - conserving at commit ({@code bnpl V012}). */
    void insertScheduleVersion(Connection unitOfWork, BnplPurchaseStore.StoredSchedule schedule, UUID refund);

    /** Births a refund's agreement version n+1 governing {@code scheduleVersion} - a copy of version n. */
    Instant insertAgreementVersion(Connection unitOfWork, BnplAgreement agreement, UUID refund, int scheduleVersion);

    /** Births the refund and its adjustments; the database stamps its instant and re-judges the bound under B5 and B4. */
    Instant insertRefund(Connection unitOfWork, BnplRefund refund);

    /**
     * Whether the purchase has an {@code OPEN} or {@code MERCHANT_RESPONDED} dispute (`P12-TSK-023`, ADR-0106 decision 7) -
     * read under the caller's B5, so a dispute opened or resolved concurrently is ordered before or after it.
     */
    boolean disputeOpen(Connection unitOfWork, BnplPurchaseId purchase);

    /**
     * {@code FINANCED -> FULLY_REFUNDED}, conditional on {@code FINANCED} - whether it moved; {@code bnpl V012}'s edge
     * function admits it only when the purchase's refunds total its gross.
     */
    boolean fullyRefunded(Connection unitOfWork, BnplPurchaseId purchase);

    /** {@code merchant}'s refunds of its purchase {@code purchase}, oldest first - {@code merchant_id = ?} in the statement. */
    List<BnplRefund> atMerchant(Connection unitOfWork, BnplPurchaseId purchase, UUID merchant);

    /**
     * The purchase a refund of {@code merchant}'s is of - {@code merchant_id = ?} in the statement: the second rank of the
     * merchant's transaction report's {@code BNPL_REFUND} identifier ({@code -016}'s carry). Another merchant's refund and
     * an absent one are the same empty answer.
     */
    Optional<BnplPurchaseId> purchaseOfRefundAtMerchant(Connection unitOfWork, UUID refund, UUID merchant);

    /**
     * The purchase as a refund holds it under B5: the purchase, its financing's gross, fee and net, and its obligation.
     * RESTRICTED-FINANCIAL.
     */
    record LockedPurchase(BnplPurchase purchase, Money gross, Money fee, Money net, BnplObligationId obligation) {

        public LockedPurchase {
            Objects.requireNonNull(purchase, "purchase must not be null");
            Objects.requireNonNull(gross, "gross must not be null");
            Objects.requireNonNull(fee, "fee must not be null");
            Objects.requireNonNull(net, "net must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            if (!fee.plus(net).equals(gross)) {
                throw new IllegalStateException("a merchant financing conserves its purchase: gross = fee + net (INV-BNPL-04)");
            }
        }

        @Override
        public String toString() {
            return "LockedPurchase[" + purchase.id().value() + ", " + purchase.status() + "]";
        }
    }
}
