package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link PaymentAttempt}. Typed (ADR-0013), UUIDv7 by construction — the value the
 * capture posting is keyed by ({@code payment-capture:&lt;attemptId&gt;}, ADR-0048) and the
 * attempt side of every refund's reference ({@code P5-TSK-008}).
 */
public final class PaymentAttemptId extends EntityId {

    private PaymentAttemptId(UUID value) {
        super(value);
    }

    public static PaymentAttemptId next(IdGenerator ids) {
        return new PaymentAttemptId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static PaymentAttemptId of(UUID value) {
        return new PaymentAttemptId(value);
    }
}
