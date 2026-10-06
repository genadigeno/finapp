package com.finapp.crossborder;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where cross-border offers rest (`P9-TSK-018`, {@code crossborder V004}): the claim's request, one per
 * claim key, and the frozen offer, one per quote and per request - both append-only by grant.
 */
public interface OfferStore {

    /** A quote's claim: who, which beneficiary, the pinned corridor version, what was asked, any re-screen. */
    record RequestRow(
            UUID id,
            String claimKey,
            UUID owner,
            BeneficiaryId beneficiary,
            CorridorPolicyId corridorPolicy,
            CorridorKey corridor,
            boolean fixedSource,
            Money amount,
            Optional<UUID> rescreen,
            Instant createdAt) {
        public RequestRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(claimKey, "claimKey must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(beneficiary, "beneficiary must not be null");
            Objects.requireNonNull(corridorPolicy, "corridorPolicy must not be null");
            Objects.requireNonNull(corridor, "corridor must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(rescreen, "rescreen must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    /** The frozen offer beside fx's quote. */
    record OfferRow(
            UUID id,
            UUID requestId,
            UUID quoteId,
            UUID owner,
            BeneficiaryId beneficiary,
            CorridorPolicyId corridorPolicy,
            CorridorKey corridor,
            Money fee,
            Money source,
            Money totalDebit,
            Money destination,
            int deliveryEstimateHours,
            Instant createdAt) {
        public OfferRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(quoteId, "quoteId must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(beneficiary, "beneficiary must not be null");
            Objects.requireNonNull(corridorPolicy, "corridorPolicy must not be null");
            Objects.requireNonNull(corridor, "corridor must not be null");
            Objects.requireNonNull(fee, "fee must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(totalDebit, "totalDebit must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    Optional<RequestRow> requestByClaim(Connection unitOfWork, String claimKey);

    /** Inserts {@code request}; false when its claim key already has one. */
    boolean insertRequest(Connection unitOfWork, RequestRow request);

    Optional<OfferRow> offerOfRequest(Connection unitOfWork, UUID requestId);

    void insertOffer(Connection unitOfWork, OfferRow offer);

    /** {@code owner}'s offer on quote {@code quoteId}, if it is theirs. */
    Optional<OfferRow> offerOwned(Connection unitOfWork, UUID quoteId, UUID owner);
}
