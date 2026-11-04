package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A regulatory obligation version's identity (`P14-TSK-018`; {@code accounting V010}). */
public final class RegulatoryObligationId extends EntityId {

    private RegulatoryObligationId(UUID value) {
        super(value);
    }

    public static RegulatoryObligationId next(IdGenerator ids) {
        return new RegulatoryObligationId(ids.next());
    }

    public static RegulatoryObligationId of(UUID value) {
        return new RegulatoryObligationId(value);
    }
}
