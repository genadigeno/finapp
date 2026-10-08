package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit decision's identity (`P10-TSK-016`). */
public final class CreditDecisionId extends EntityId {

    private CreditDecisionId(UUID value) {
        super(value);
    }

    public static CreditDecisionId next(IdGenerator ids) {
        return new CreditDecisionId(ids.next());
    }

    public static CreditDecisionId of(UUID value) {
        return new CreditDecisionId(value);
    }
}
