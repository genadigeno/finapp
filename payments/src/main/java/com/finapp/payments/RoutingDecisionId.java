package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link RoutingDecision} — what the operator's explanation read answers by (`P7-TSK-003`, ADR-0060). Typed (ADR-0013), UUIDv7 by construction.
 */
public final class RoutingDecisionId extends EntityId {

    private RoutingDecisionId(UUID value) {
        super(value);
    }

    public static RoutingDecisionId next(IdGenerator ids) {
        return new RoutingDecisionId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static RoutingDecisionId of(UUID value) {
        return new RoutingDecisionId(value);
    }
}
