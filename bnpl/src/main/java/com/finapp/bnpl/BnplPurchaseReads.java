package com.finapp.bnpl;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The merchant's reads of its BNPL purchases (`P12-TSK-014`; {@code PHASE_12_PLAN.md} sections 9 and 11; ADR-0052,
 * {@code INV-MER-01}, {@code INV-CRD-04}): one purchase made at the merchant, as {@link MerchantBnplPurchaseView}'s closed
 * field set - with its refunds since `P12-TSK-021` ({@link MerchantBnplRefundView}'s closed set, `-014`'s carry).
 * <strong>The tenant is a predicate in every statement</strong> ({@link BnplPurchaseStore#atMerchant}'s and
 * {@link BnplRefundStore#atMerchant}'s {@code merchant_id = ?}), never a post-filter: another merchant's purchase and an
 * absent one are the same empty answer, so the door's {@code 404} reveals nothing of existence. The merchant comes from
 * the authenticated key alone, by value - bnpl has no edge to {@code merchant}. It decides nothing and writes nothing; the
 * door runs it in one {@code REPEATABLE READ} read-only snapshot.
 */
public final class BnplPurchaseReads {

    private final BnplPurchaseStore store;
    private final Optional<BnplRefundStore> refunds;

    /** The purchase reads alone - `P12-TSK-014`'s, before refunds existed. */
    public BnplPurchaseReads(BnplPurchaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.refunds = Optional.empty();
    }

    /** The purchase reads with the purchase's refunds (`P12-TSK-021`). */
    public BnplPurchaseReads(BnplPurchaseStore store, BnplRefundStore refunds) {
        this.store = Objects.requireNonNull(store, "store");
        this.refunds = Optional.of(Objects.requireNonNull(refunds, "refunds"));
    }

    /** {@code merchant}'s BNPL purchase {@code id}; another merchant's is the same empty answer as an absent one. */
    public Optional<MerchantBnplPurchaseView> forMerchant(Connection unitOfWork, UUID merchant, BnplPurchaseId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchant, "merchant");
        Optional<MerchantBnplPurchaseView> purchase = store.atMerchant(unitOfWork, id, merchant);
        if (purchase.isEmpty() || refunds.isEmpty()) {
            return purchase;
        }
        List<MerchantBnplRefundView> read = refunds.get().atMerchant(unitOfWork, id, merchant).stream()
                .map(MerchantBnplRefundView::of).toList();
        return Optional.of(purchase.get().withRefunds(read));
    }

    /**
     * The purchase {@code merchant}'s refund {@code refund} is of - empty for another merchant's refund or an absent one:
     * the second rank behind the merchant transaction report's {@code BNPL_REFUND} identifier (`P12-TSK-016`'s carry).
     */
    public Optional<BnplPurchaseId> refundAtMerchant(Connection unitOfWork, UUID merchant, UUID refund) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(refund, "refund");
        return refunds.flatMap(reader -> reader.purchaseOfRefundAtMerchant(unitOfWork, refund, merchant));
    }
}
