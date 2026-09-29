package com.finapp.reconciliation;

import com.finapp.sharedkernel.id.EntityId;
import java.util.UUID;

/** A reconciliation run's identity (`P8-TSK-011`) — typed for the event envelope. */
public final class RunId extends EntityId {

    private RunId(UUID value) {
        super(value);
    }

    public static RunId of(UUID value) {
        return new RunId(value);
    }
}
