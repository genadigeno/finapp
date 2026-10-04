package com.finapp.fx;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** An FX quote's identity (`P9-TSK-008`). */
public final class FxQuoteId extends EntityId {

    private FxQuoteId(UUID value) {
        super(value);
    }

    public static FxQuoteId next(IdGenerator ids) {
        return new FxQuoteId(ids.next());
    }

    public static FxQuoteId of(UUID value) {
        return new FxQuoteId(value);
    }
}
