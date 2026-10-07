package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit profile's identity (`P10-TSK-004`). */
public final class CreditProfileId extends EntityId {

    private CreditProfileId(UUID value) {
        super(value);
    }

    public static CreditProfileId next(IdGenerator ids) {
        return new CreditProfileId(ids.next());
    }

    public static CreditProfileId of(UUID value) {
        return new CreditProfileId(value);
    }
}
