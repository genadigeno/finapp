package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A BNPL refund's identity (`P12-TSK-002`; the table is `P12-TSK-021`'s).
 */
public final class BnplRefundId extends EntityId {

    private BnplRefundId(UUID value) {
        super(value);
    }

    public static BnplRefundId next(IdGenerator ids) {
        return new BnplRefundId(ids.next());
    }

    public static BnplRefundId of(UUID value) {
        return new BnplRefundId(value);
    }
}
