package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link PaymentIntent}. Typed (ADR-0013), UUIDv7 by construction — the value a
 * customer's status query answers by ({@code P5-TSK-009}) and the intent side of the
 * intent–attempt join ({@code P5-TSK-008}).
 */
public final class PaymentIntentId extends EntityId {

    private PaymentIntentId(UUID value) {
        super(value);
    }

    public static PaymentIntentId next(IdGenerator ids) {
        return new PaymentIntentId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static PaymentIntentId of(UUID value) {
        return new PaymentIntentId(value);
    }
}
