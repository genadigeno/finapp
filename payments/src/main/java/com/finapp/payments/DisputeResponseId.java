package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies one {@link DisputeResponse} (`P7-TSK-014`). Typed (ADR-0013), UUIDv7. */
public final class DisputeResponseId extends EntityId {

    private DisputeResponseId(UUID value) {
        super(value);
    }

    public static DisputeResponseId next(IdGenerator ids) {
        return new DisputeResponseId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static DisputeResponseId of(UUID value) {
        return new DisputeResponseId(value);
    }
}
