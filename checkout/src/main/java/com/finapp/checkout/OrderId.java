package com.finapp.checkout;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies an {@link Order} (`P6-TSK-006`). Typed (ADR-0013), UUIDv7 by construction.
 *
 * <p>The identifier of a permanent commercial fact: an order outlives the session that produced
 * it, the offer that preceded it and the payment that paid for it.
 */
public final class OrderId extends EntityId {

    private OrderId(UUID value) {
        super(value);
    }

    public static OrderId next(IdGenerator ids) {
        return new OrderId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static OrderId of(UUID value) {
        return new OrderId(value);
    }
}
