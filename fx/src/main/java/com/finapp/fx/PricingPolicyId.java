package com.finapp.fx;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A pricing policy version's identifier (`P9-TSK-007`) - a UUIDv7 (ADR-0013). */
public final class PricingPolicyId extends EntityId {

    private PricingPolicyId(UUID value) {
        super(value);
    }

    public static PricingPolicyId next(IdGenerator ids) {
        return new PricingPolicyId(ids.next());
    }

    public static PricingPolicyId of(UUID value) {
        return new PricingPolicyId(value);
    }
}
