package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit decision consumption's identity (`P11-TSK-001`). */
public final class CreditDecisionConsumptionId extends EntityId {

    private CreditDecisionConsumptionId(UUID value) {
        super(value);
    }

    public static CreditDecisionConsumptionId next(IdGenerator ids) {
        return new CreditDecisionConsumptionId(ids.next());
    }

    public static CreditDecisionConsumptionId of(UUID value) {
        return new CreditDecisionConsumptionId(value);
    }
}
