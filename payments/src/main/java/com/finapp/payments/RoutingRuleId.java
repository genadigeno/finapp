package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link RoutingRule} within its version — the join key for the rule's ordered candidate rails (`P7-TSK-003`, ADR-0060). Typed (ADR-0013), UUIDv7 by construction.
 */
public final class RoutingRuleId extends EntityId {

    private RoutingRuleId(UUID value) {
        super(value);
    }

    public static RoutingRuleId next(IdGenerator ids) {
        return new RoutingRuleId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static RoutingRuleId of(UUID value) {
        return new RoutingRuleId(value);
    }
}
