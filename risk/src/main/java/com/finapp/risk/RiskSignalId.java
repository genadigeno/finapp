package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A risk signal's identity (`P13-TSK-001`; the table is `P13-TSK-005`'s (`risk V003`)).
 */
public final class RiskSignalId extends EntityId {

    private RiskSignalId(UUID value) {
        super(value);
    }

    public static RiskSignalId next(IdGenerator ids) {
        return new RiskSignalId(ids.next());
    }

    public static RiskSignalId of(UUID value) {
        return new RiskSignalId(value);
    }
}
