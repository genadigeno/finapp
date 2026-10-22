package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A risk assessment's identity - the sealed record of one evaluation (`P13-TSK-001`; the table is `P13-TSK-004`'s (`risk V007`)).
 */
public final class RiskAssessmentId extends EntityId {

    private RiskAssessmentId(UUID value) {
        super(value);
    }

    public static RiskAssessmentId next(IdGenerator ids) {
        return new RiskAssessmentId(ids.next());
    }

    public static RiskAssessmentId of(UUID value) {
        return new RiskAssessmentId(value);
    }
}
