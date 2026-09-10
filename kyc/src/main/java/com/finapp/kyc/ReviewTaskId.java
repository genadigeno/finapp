package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link ReviewTask}. Typed, so a check identifier cannot stand in (ADR-0013). */
public final class ReviewTaskId extends EntityId {

    private ReviewTaskId(UUID value) {
        super(value);
    }

    public static ReviewTaskId next(IdGenerator ids) {
        return new ReviewTaskId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static ReviewTaskId of(UUID value) {
        return new ReviewTaskId(value);
    }
}
