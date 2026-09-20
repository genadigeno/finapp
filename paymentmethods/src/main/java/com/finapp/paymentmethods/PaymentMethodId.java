package com.finapp.paymentmethods;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link PaymentMethod}. Typed (ADR-0013), UUIDv7 by construction. */
public final class PaymentMethodId extends EntityId {

    private PaymentMethodId(UUID value) {
        super(value);
    }

    public static PaymentMethodId next(IdGenerator ids) {
        return new PaymentMethodId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static PaymentMethodId of(UUID value) {
        return new PaymentMethodId(value);
    }
}
