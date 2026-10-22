package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * An alert's identity - born once per subject, rule and window (`P13-TSK-001`; the table is `P13-TSK-015`'s (`risk V008`)).
 */
public final class RiskAlertId extends EntityId {

    private RiskAlertId(UUID value) {
        super(value);
    }

    public static RiskAlertId next(IdGenerator ids) {
        return new RiskAlertId(ids.next());
    }

    public static RiskAlertId of(UUID value) {
        return new RiskAlertId(value);
    }
}
