package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL Purchase as recorded (`P12-TSK-013`, {@code bnpl V006}; {@code BNPL_LIFECYCLES.md} section 4.5) - the financed
 * commercial fact's machine row, no money: its offer, the checkout session it pays, the merchant and the party by value,
 * the product and plan, and its business date - the purchase's own day, stamped by the database after the acceptance's
 * locks, which schedule version 1 starts on and every principal fact of the obligation is floored at. Its gross, fee and
 * net are its {@link MerchantFinancing}'s.
 *
 * @param order checkout's order, once {@code FINANCED} - the order born with the financing entry
 */
public record BnplPurchase(
        BnplPurchaseId id,
        BnplOfferId offer,
        UUID checkoutSession,
        UUID party,
        UUID merchant,
        BnplProduct product,
        BnplPlan plan,
        CurrencyCode currency,
        BnplPurchaseStatus status,
        LocalDate businessDate,
        Instant authorisedAt,
        Optional<Instant> financedAt,
        Optional<UUID> order,
        CorrelationId correlation) {

    public BnplPurchase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(authorisedAt, "authorisedAt must not be null");
        Objects.requireNonNull(financedAt, "financedAt must not be null");
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
    }

    /** Identifiers and the state only - never the merchant or the party ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplPurchase[" + id.value() + ", " + product + ", " + status + "]";
    }
}
