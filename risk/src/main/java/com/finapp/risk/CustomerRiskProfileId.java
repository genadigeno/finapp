package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import java.util.UUID;

/**
 * A customer risk profile's identity (`P13-TSK-007`; {@code risk V006}): its party's identifier - one profile per party,
 * the head's primary key - typed so the profile's events and audit records name a profile, never a bare value. The
 * aggregate id of {@code risk.CustomerRiskRatingChanged}.
 */
public final class CustomerRiskProfileId extends EntityId {

    private CustomerRiskProfileId(UUID value) {
        super(value);
    }

    /** The profile of {@code partyId}. */
    public static CustomerRiskProfileId of(UUID partyId) {
        return new CustomerRiskProfileId(partyId);
    }
}
