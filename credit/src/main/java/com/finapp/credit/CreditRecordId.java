package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit record's identity (`P10-TSK-006`). */
public final class CreditRecordId extends EntityId {

    private CreditRecordId(UUID value) {
        super(value);
    }

    public static CreditRecordId next(IdGenerator ids) {
        return new CreditRecordId(ids.next());
    }

    public static CreditRecordId of(UUID value) {
        return new CreditRecordId(value);
    }
}
