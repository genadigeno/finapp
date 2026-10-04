package com.finapp.fx;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A booked FX trade's identity (`P9-TSK-009`). */
public final class FxTradeId extends EntityId {

    private FxTradeId(UUID value) {
        super(value);
    }

    public static FxTradeId next(IdGenerator ids) {
        return new FxTradeId(ids.next());
    }

    public static FxTradeId of(UUID value) {
        return new FxTradeId(value);
    }
}
