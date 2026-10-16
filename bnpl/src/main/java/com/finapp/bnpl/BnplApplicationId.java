package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A BNPL application's identity (`P12-TSK-002`; the table is `P12-TSK-010`'s).
 */
public final class BnplApplicationId extends EntityId {

    private BnplApplicationId(UUID value) {
        super(value);
    }

    public static BnplApplicationId next(IdGenerator ids) {
        return new BnplApplicationId(ids.next());
    }

    public static BnplApplicationId of(UUID value) {
        return new BnplApplicationId(value);
    }
}
