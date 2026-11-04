package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A prior-period adjustment's identity (`P14-TSK-013`; {@code accounting V009}). */
public final class PriorPeriodAdjustmentId extends EntityId {

    private PriorPeriodAdjustmentId(UUID value) {
        super(value);
    }

    public static PriorPeriodAdjustmentId next(IdGenerator ids) {
        return new PriorPeriodAdjustmentId(ids.next());
    }

    public static PriorPeriodAdjustmentId of(UUID value) {
        return new PriorPeriodAdjustmentId(value);
    }
}
