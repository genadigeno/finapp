package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit assessment's identity (`P10-TSK-011`). */
public final class CreditAssessmentId extends EntityId {

    private CreditAssessmentId(UUID value) {
        super(value);
    }

    public static CreditAssessmentId next(IdGenerator ids) {
        return new CreditAssessmentId(ids.next());
    }

    public static CreditAssessmentId of(UUID value) {
        return new CreditAssessmentId(value);
    }
}
