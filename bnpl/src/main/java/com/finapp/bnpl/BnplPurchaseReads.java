package com.finapp.bnpl;

import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The merchant's reads of its BNPL purchases (`P12-TSK-014`; {@code PHASE_12_PLAN.md} sections 9 and 11; ADR-0052,
 * {@code INV-MER-01}, {@code INV-CRD-04}): one purchase made at the merchant, as {@link MerchantBnplPurchaseView}'s closed
 * field set. <strong>The tenant is a predicate in the statement</strong> ({@link BnplPurchaseStore#atMerchant}'s
 * {@code merchant_id = ?}), never a post-filter: another merchant's purchase and an absent one are the same empty answer,
 * so the door's {@code 404} reveals nothing of existence. The merchant comes from the authenticated key alone, by value -
 * bnpl has no edge to {@code merchant}. It decides nothing and writes nothing; the door runs it in one
 * {@code REPEATABLE READ} read-only snapshot.
 */
@RequiredArgsConstructor
public final class BnplPurchaseReads {

    @NonNull private final BnplPurchaseStore store;

    /** {@code merchant}'s BNPL purchase {@code id}; another merchant's is the same empty answer as an absent one. */
    public Optional<MerchantBnplPurchaseView> forMerchant(Connection unitOfWork, UUID merchant, BnplPurchaseId id) {
        return store.atMerchant(Objects.requireNonNull(unitOfWork, "unitOfWork"), Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(merchant, "merchant"));
    }
}
