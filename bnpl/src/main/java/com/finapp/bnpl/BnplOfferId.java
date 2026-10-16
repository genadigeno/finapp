package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A BNPL offer's identity (`P12-TSK-002`; the table is `P12-TSK-011`'s).
 */
public final class BnplOfferId extends EntityId {

    private BnplOfferId(UUID value) {
        super(value);
    }

    public static BnplOfferId next(IdGenerator ids) {
        return new BnplOfferId(ids.next());
    }

    public static BnplOfferId of(UUID value) {
        return new BnplOfferId(value);
    }
}
