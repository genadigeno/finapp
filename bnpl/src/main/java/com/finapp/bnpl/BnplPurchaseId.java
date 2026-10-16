package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A BNPL purchase's identity - the financed commercial fact (`P12-TSK-002`; the table is `P12-TSK-013`'s).
 */
public final class BnplPurchaseId extends EntityId {

    private BnplPurchaseId(UUID value) {
        super(value);
    }

    public static BnplPurchaseId next(IdGenerator ids) {
        return new BnplPurchaseId(ids.next());
    }

    public static BnplPurchaseId of(UUID value) {
        return new BnplPurchaseId(value);
    }
}
