package com.finapp.payments;

/**
 * A response's idempotency key already names a DIFFERENT response (`P7-TSK-014`) — another
 * dispute or another kind. `V022`'s dispatch key binds a key to one response for ever, for longer
 * than the claim that first carried it (the refund's {@code RefundKeyReusedException} rule): the
 * boundary answers the kernel's idempotency conflict ({@code INV-IDEM-03}).
 */
public final class DisputeResponseKeyReusedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public DisputeResponseKeyReusedException() {
        super("the idempotency key already names a different dispute response");
    }
}
