package com.finapp.reconciliation;

import com.finapp.sharedkernel.id.EntityId;
import java.util.UUID;

/** A break's identity (`P8-TSK-010`) — typed for the event envelope's aggregate. */
public final class BreakId extends EntityId {

    private BreakId(UUID value) {
        super(value);
    }

    public static BreakId of(UUID value) {
        return new BreakId(value);
    }
}
