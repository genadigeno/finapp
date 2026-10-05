package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A counterparty screening's identifier (`P9-TSK-016`) - a UUIDv7 (ADR-0013), the name's AAD. */
public final class CounterpartyScreeningId extends EntityId {

    private CounterpartyScreeningId(UUID value) {
        super(value);
    }

    public static CounterpartyScreeningId next(IdGenerator ids) {
        return new CounterpartyScreeningId(ids.next());
    }

    public static CounterpartyScreeningId of(UUID value) {
        return new CounterpartyScreeningId(value);
    }
}
