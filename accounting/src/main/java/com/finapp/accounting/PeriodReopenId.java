package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A reopen proposal's identity (`P14-TSK-012`; {@code accounting V008}'s {@code period_reopen}). */
public final class PeriodReopenId extends EntityId {

    private PeriodReopenId(UUID value) {
        super(value);
    }

    public static PeriodReopenId next(IdGenerator ids) {
        return new PeriodReopenId(ids.next());
    }

    public static PeriodReopenId of(UUID value) {
        return new PeriodReopenId(value);
    }
}
