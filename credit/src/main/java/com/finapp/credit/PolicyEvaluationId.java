package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A policy evaluation's identity (`P10-TSK-013`). */
public final class PolicyEvaluationId extends EntityId {

    private PolicyEvaluationId(UUID value) {
        super(value);
    }

    public static PolicyEvaluationId next(IdGenerator ids) {
        return new PolicyEvaluationId(ids.next());
    }

    public static PolicyEvaluationId of(UUID value) {
        return new PolicyEvaluationId(value);
    }
}
