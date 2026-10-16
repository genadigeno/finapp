package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A BNPL dispute's identity (`P12-TSK-002`; the table is `P12-TSK-023`'s).
 */
public final class BnplDisputeId extends EntityId {

    private BnplDisputeId(UUID value) {
        super(value);
    }

    public static BnplDisputeId next(IdGenerator ids) {
        return new BnplDisputeId(ids.next());
    }

    public static BnplDisputeId of(UUID value) {
        return new BnplDisputeId(value);
    }
}
