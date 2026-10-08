package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** An underwriting case's identity (`P10-TSK-018`). */
public final class UnderwritingCaseId extends EntityId {

    private UnderwritingCaseId(UUID value) {
        super(value);
    }

    public static UnderwritingCaseId next(IdGenerator ids) {
        return new UnderwritingCaseId(ids.next());
    }

    public static UnderwritingCaseId of(UUID value) {
        return new UnderwritingCaseId(value);
    }
}
