package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit data request's identity (`P10-TSK-006`). */
public final class CreditDataRequestId extends EntityId {

    private CreditDataRequestId(UUID value) {
        super(value);
    }

    public static CreditDataRequestId next(IdGenerator ids) {
        return new CreditDataRequestId(ids.next());
    }

    public static CreditDataRequestId of(UUID value) {
        return new CreditDataRequestId(value);
    }
}
