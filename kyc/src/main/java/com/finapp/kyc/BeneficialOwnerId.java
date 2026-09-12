package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link BeneficialOwner}. Typed, so a case identifier cannot stand in (ADR-0013). */
public final class BeneficialOwnerId extends EntityId {

    private BeneficialOwnerId(UUID value) {
        super(value);
    }

    public static BeneficialOwnerId next(IdGenerator ids) {
        return new BeneficialOwnerId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static BeneficialOwnerId of(UUID value) {
        return new BeneficialOwnerId(value);
    }
}
