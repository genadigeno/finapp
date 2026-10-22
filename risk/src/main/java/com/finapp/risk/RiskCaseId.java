package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A case's identity (`P13-TSK-001`; the table is `P13-TSK-016`'s (`risk V009`)).
 */
public final class RiskCaseId extends EntityId {

    private RiskCaseId(UUID value) {
        super(value);
    }

    public static RiskCaseId next(IdGenerator ids) {
        return new RiskCaseId(ids.next());
    }

    public static RiskCaseId of(UUID value) {
        return new RiskCaseId(value);
    }
}
