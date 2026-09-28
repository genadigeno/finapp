package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies one {@link DisputeEvidence} document (`P7-TSK-014`). Typed (ADR-0013), UUIDv7. */
public final class DisputeEvidenceId extends EntityId {

    private DisputeEvidenceId(UUID value) {
        super(value);
    }

    public static DisputeEvidenceId next(IdGenerator ids) {
        return new DisputeEvidenceId(ids.next());
    }

    /** For values read back from storage or a request path. Validates shape, including UUIDv7. */
    public static DisputeEvidenceId of(UUID value) {
        return new DisputeEvidenceId(value);
    }
}
