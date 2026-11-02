package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** One close check attempt's identity (`P14-TSK-011`; {@code accounting V007}'s {@code close_check}). */
public final class CloseCheckId extends EntityId {

    private CloseCheckId(UUID value) {
        super(value);
    }

    public static CloseCheckId next(IdGenerator ids) {
        return new CloseCheckId(ids.next());
    }

    public static CloseCheckId of(UUID value) {
        return new CloseCheckId(value);
    }
}
