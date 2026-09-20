package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Refund}. Typed (ADR-0013), UUIDv7 by construction — the value the refund
 * posting is keyed by ({@code payment-refund:&lt;refundId&gt;}, ADR-0048).
 */
public final class RefundId extends EntityId {

    private RefundId(UUID value) {
        super(value);
    }

    public static RefundId next(IdGenerator ids) {
        return new RefundId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static RefundId of(UUID value) {
        return new RefundId(value);
    }
}
