package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link KycCase}. Typed, so a customer identifier cannot stand in (ADR-0013). */
public final class KycCaseId extends EntityId {

    private KycCaseId(UUID value) {
        super(value);
    }

    public static KycCaseId next(IdGenerator ids) {
        return new KycCaseId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static KycCaseId of(UUID value) {
        return new KycCaseId(value);
    }
}
