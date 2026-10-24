package com.finapp.crossborder;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What a Phase 13 seam judges (`P9-TSK-016`, ADR-0081 point 8): who is sending, which offer they are
 * authorising, along which corridor, and how much leaves them - identifiers and an amount, never a name.
 * The authorization (`P9-TSK-019`) builds it from the accepted offer.
 *
 * <p>{@code customerId} is the <strong>owning party's</strong> id - the authorization's {@code owner}, the
 * party the offer and the beneficiary belong to.
 *
 * <p>{@code quoteId} (`P13-TSK-011`, added beside the Phase 9 fields): the offer's quote - the movement's
 * identity before the payment's id exists (it is minted after the seams), one payment per quote by
 * {@code payment_one_per_quote}. Through it an implementation finds the offer it judges - the beneficiary
 * and the quote's figures - in its own reads; the verdict stays two values with no reason.
 */
public record CrossBorderInstruction(UUID customerId, UUID quoteId, CorridorKey corridor, Money sourceAmount) {

    public CrossBorderInstruction {
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(corridor, "corridor must not be null");
        Objects.requireNonNull(sourceAmount, "sourceAmount must not be null");
        if (!sourceAmount.currency().equals(corridor.source())) {
            throw new IllegalArgumentException("the source amount is in the corridor's source currency");
        }
    }
}
