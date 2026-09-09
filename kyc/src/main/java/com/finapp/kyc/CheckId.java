package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link VerificationCheck}. Typed, so a case identifier cannot stand in (ADR-0013). */
public final class CheckId extends EntityId {

    private CheckId(UUID value) {
        super(value);
    }

    public static CheckId next(IdGenerator ids) {
        return new CheckId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static CheckId of(UUID value) {
        return new CheckId(value);
    }
}
