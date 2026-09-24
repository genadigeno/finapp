package com.finapp.payments;

import java.io.Serial;

/**
 * The idempotency key presented for a refund already carries a DIFFERENT refund
 * ({@code INV-IDEM-03}; the Phase 6 → 7 transition).
 *
 * <p>A key is bound to its refund for longer than the claim that first carried it: {@code V008}
 * keeps one refund row per dispatch key for ever. Within the claim's retention the claim's own
 * fingerprint refuses different facts first; after it, this refusal is what stops a reused key
 * from dispatching into a unique index (answered as a {@code 500}, before this exception existed)
 * — and nothing is written. The surface renders it as the kernel's own {@code api.Conflict}, so
 * a caller cannot tell which rank refused it.
 */
public final class RefundKeyReusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public RefundKeyReusedException() {
        // Names neither the key nor the refund: the key is the caller's own input, and which
        // refund it carries is another request's fact (the IdempotencyConflictException rule).
        super("A refund idempotency key already carries a different refund (INV-IDEM-03, V008)");
    }
}
