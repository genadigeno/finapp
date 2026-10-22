package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A decision override's identity (`P13-TSK-001`; the table is `P13-TSK-004`'s (`risk V007`); the machine `P13-TSK-012`'s).
 */
public final class DecisionOverrideId extends EntityId {

    private DecisionOverrideId(UUID value) {
        super(value);
    }

    public static DecisionOverrideId next(IdGenerator ids) {
        return new DecisionOverrideId(ids.next());
    }

    public static DecisionOverrideId of(UUID value) {
        return new DecisionOverrideId(value);
    }
}
