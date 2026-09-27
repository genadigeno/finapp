package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Dispute} (`P7-TSK-012`). Typed (ADR-0013), UUIDv7 by construction — and,
 * from `P7-TSK-013`, the suffix of each stage's posting key ({@code dispute-chargeback:<id>}).
 */
public final class DisputeId extends EntityId {

    private DisputeId(UUID value) {
        super(value);
    }

    public static DisputeId next(IdGenerator ids) {
        return new DisputeId(ids.next());
    }

    /** For values read back from storage or a request path. Validates shape, including UUIDv7. */
    public static DisputeId of(UUID value) {
        return new DisputeId(value);
    }
}
