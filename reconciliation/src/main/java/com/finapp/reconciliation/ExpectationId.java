package com.finapp.reconciliation;

import com.finapp.sharedkernel.id.EntityId;
import java.util.UUID;

/** A settlement expectation's identity (`P8-TSK-011`) — typed for the event envelope. */
public final class ExpectationId extends EntityId {

    private ExpectationId(UUID value) {
        super(value);
    }

    public static ExpectationId of(UUID value) {
        return new ExpectationId(value);
    }
}
