package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A downstream action's identity - the record in risk of an effect the owning domain performs (`P13-TSK-001`; the table is `P13-TSK-017`'s (`risk V010`)).
 */
public final class RiskActionId extends EntityId {

    private RiskActionId(UUID value) {
        super(value);
    }

    public static RiskActionId next(IdGenerator ids) {
        return new RiskActionId(ids.next());
    }

    public static RiskActionId of(UUID value) {
        return new RiskActionId(value);
    }
}
