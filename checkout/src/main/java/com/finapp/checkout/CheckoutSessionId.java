package com.finapp.checkout;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link CheckoutSession} (`P6-TSK-006`). Typed (ADR-0013), UUIDv7 by
 * construction.
 *
 * <p><strong>Not the token.</strong> This is what the platform calls the session - it appears in
 * operator views, in the order's reference and in logs. {@link CheckoutSessionToken} is what
 * proves a caller holds it, and the two exist separately so that naming a session is not the
 * same act as being able to act on one ({@code SessionToken}'s recorded rule).
 */
public final class CheckoutSessionId extends EntityId {

    private CheckoutSessionId(UUID value) {
        super(value);
    }

    public static CheckoutSessionId next(IdGenerator ids) {
        return new CheckoutSessionId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static CheckoutSessionId of(UUID value) {
        return new CheckoutSessionId(value);
    }
}
