package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit decision request's identity (`P10-TSK-014`). */
public final class DecisionRequestId extends EntityId {

    private DecisionRequestId(UUID value) {
        super(value);
    }

    public static DecisionRequestId next(IdGenerator ids) {
        return new DecisionRequestId(ids.next());
    }

    public static DecisionRequestId of(UUID value) {
        return new DecisionRequestId(value);
    }
}
