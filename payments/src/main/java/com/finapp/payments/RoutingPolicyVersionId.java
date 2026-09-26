package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link RoutingPolicyVersion} — the value INV-HIST-04 pins on every decision (`P7-TSK-003`, ADR-0060). Typed (ADR-0013), UUIDv7 by construction.
 */
public final class RoutingPolicyVersionId extends EntityId {

    private RoutingPolicyVersionId(UUID value) {
        super(value);
    }

    public static RoutingPolicyVersionId next(IdGenerator ids) {
        return new RoutingPolicyVersionId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static RoutingPolicyVersionId of(UUID value) {
        return new RoutingPolicyVersionId(value);
    }
}
