package com.finapp.fx;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A cover's identity (`P9-TSK-012`) - the aggregate its events name. */
public final class FxCoverId extends EntityId {

    private FxCoverId(UUID value) {
        super(value);
    }

    public static FxCoverId next(IdGenerator ids) {
        return new FxCoverId(ids.next());
    }

    public static FxCoverId of(UUID value) {
        return new FxCoverId(value);
    }
}
