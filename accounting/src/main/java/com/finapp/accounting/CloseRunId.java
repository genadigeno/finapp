package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A close run's identity (`P14-TSK-011`; {@code accounting V007}'s {@code close_run}). */
public final class CloseRunId extends EntityId {

    private CloseRunId(UUID value) {
        super(value);
    }

    public static CloseRunId next(IdGenerator ids) {
        return new CloseRunId(ids.next());
    }

    public static CloseRunId of(UUID value) {
        return new CloseRunId(value);
    }
}
