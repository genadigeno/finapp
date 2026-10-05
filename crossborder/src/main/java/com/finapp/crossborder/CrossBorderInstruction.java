package com.finapp.crossborder;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What a Phase 13 seam judges (`P9-TSK-016`, ADR-0081 point 8): who is sending, along which corridor,
 * and how much leaves them - identifiers and an amount, never a name. The authorization (`P9-TSK-019`)
 * builds it from the accepted offer.
 */
public record CrossBorderInstruction(UUID customerId, CorridorKey corridor, Money sourceAmount) {

    public CrossBorderInstruction {
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(corridor, "corridor must not be null");
        Objects.requireNonNull(sourceAmount, "sourceAmount must not be null");
        if (!sourceAmount.currency().equals(corridor.source())) {
            throw new IllegalArgumentException("the source amount is in the corridor's source currency");
        }
    }
}
