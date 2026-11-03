package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A close proposal's identity (`P14-TSK-012`; {@code accounting V008}'s {@code period_close}). */
public final class PeriodCloseId extends EntityId {

    private PeriodCloseId(UUID value) {
        super(value);
    }

    public static PeriodCloseId next(IdGenerator ids) {
        return new PeriodCloseId(ids.next());
    }

    public static PeriodCloseId of(UUID value) {
        return new PeriodCloseId(value);
    }
}
