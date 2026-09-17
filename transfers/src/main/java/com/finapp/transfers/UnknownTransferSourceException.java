package com.finapp.transfers;

import java.io.Serial;

/**
 * The named source could not be resolved as the caller's own product — unknown, somebody
 * else's, or a caller with no live customer, deliberately one indistinguishable refusal
 * ({@code INV-IDN-07}'s reasoning; {@link TransferParticipants#sourceOwnedBy}'s one empty
 * answer). A boundary mistake, never a committed outcome: thrown before any write survives,
 * and the caller's rollback takes the idempotency claim with it — the retry re-attempts
 * rather than replaying a refusal that never committed (`P3-TSK-006`'s property).
 */
public final class UnknownTransferSourceException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public UnknownTransferSourceException() {
        super("no such source product is the caller's to transfer from");
    }
}
