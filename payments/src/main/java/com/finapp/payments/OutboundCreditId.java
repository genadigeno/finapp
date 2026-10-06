package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** An outbound credit's identifier (`P9-TSK-019`) - UUIDv7, minted by the application. */
public final class OutboundCreditId extends EntityId {

    private OutboundCreditId(UUID value) {
        super(value);
    }

    public static OutboundCreditId next(IdGenerator ids) {
        return new OutboundCreditId(ids.next());
    }

    public static OutboundCreditId of(UUID value) {
        return new OutboundCreditId(value);
    }
}
