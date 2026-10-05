package com.finapp.crossborder;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A corridor policy version's identifier (`P9-TSK-015`) - a UUIDv7 (ADR-0013). */
public final class CorridorPolicyId extends EntityId {

    private CorridorPolicyId(UUID value) {
        super(value);
    }

    public static CorridorPolicyId next(IdGenerator ids) {
        return new CorridorPolicyId(ids.next());
    }

    public static CorridorPolicyId of(UUID value) {
        return new CorridorPolicyId(value);
    }
}
