package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies an {@link InformationRequest}. Typed, so a case or task identifier cannot stand in (ADR-0013). */
public final class InformationRequestId extends EntityId {

    private InformationRequestId(UUID value) {
        super(value);
    }

    public static InformationRequestId next(IdGenerator ids) {
        return new InformationRequestId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static InformationRequestId of(UUID value) {
        return new InformationRequestId(value);
    }
}
