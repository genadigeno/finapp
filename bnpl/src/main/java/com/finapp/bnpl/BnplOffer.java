package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL offer as recorded (`P12-TSK-011`, {@code bnpl V004}; {@code BNPL_LIFECYCLES.md} section 4.3) - the machine row,
 * no money: what credit approved for exactly the purchase becomes explicit, expiring terms ({@link BnplOfferStore.StoredTerms}),
 * one per application and per decision, its expiry stamped by the database as {@code LEAST(the decision's validity, the
 * checkout session's expiry, offered_at + the pinned offer validity)} (A17) - so it never outlives either.
 *
 * @param termsVersion the pinned terms version's number (read through the version row)
 * @param closedAt the database's instant of its terminal edge, once closed
 */
public record BnplOffer(
        BnplOfferId id,
        BnplApplicationId application,
        UUID party,
        BnplProduct product,
        BnplPlan plan,
        BnplOfferStatus status,
        UUID decision,
        BnplTermsVersionId termsVersionId,
        int termsVersion,
        UUID checkoutSession,
        Instant offeredAt,
        Instant expiresAt,
        Optional<Instant> closedAt,
        CorrelationId correlation) {

    public BnplOffer {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
        Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
        Objects.requireNonNull(offeredAt, "offeredAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
    }

    /** Identifiers and the state only. */
    @Override
    public String toString() {
        return "BnplOffer[" + id.value() + ", " + product + ", " + status + "]";
    }
}
