package com.finapp.ledger;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link ProjectionRebuild} (`P14-TSK-026`, ADR-0119). Typed (ADR-0013), UUIDv7, minted by the proposing
 * call.
 */
public final class ProjectionRebuildId extends EntityId {

    private ProjectionRebuildId(UUID value) {
        super(value);
    }

    public static ProjectionRebuildId next(IdGenerator ids) {
        return new ProjectionRebuildId(ids.next());
    }

    /** For values read back from storage or a URL. Validates shape, including UUIDv7. */
    public static ProjectionRebuildId of(UUID value) {
        return new ProjectionRebuildId(value);
    }
}
