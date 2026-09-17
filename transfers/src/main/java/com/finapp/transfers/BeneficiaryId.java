package com.finapp.transfers;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link Beneficiary}. Typed (ADR-0013), UUIDv7 by construction. */
public final class BeneficiaryId extends EntityId {

    private BeneficiaryId(UUID value) {
        super(value);
    }

    public static BeneficiaryId next(IdGenerator ids) {
        return new BeneficiaryId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static BeneficiaryId of(UUID value) {
        return new BeneficiaryId(value);
    }
}
