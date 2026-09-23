package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link PayoutDestination}. Typed (ADR-0013), UUIDv7 by construction.
 *
 * <p><strong>The identifier is also the destination's version</strong> (`P6-TSK-011`,
 * ADR-0056): every change is its own immutable row, so the id a payout records is the exact
 * destination it was sent to — never a row that was edited afterwards.
 */
public final class PayoutDestinationId extends EntityId {

    private PayoutDestinationId(UUID value) {
        super(value);
    }

    public static PayoutDestinationId next(IdGenerator ids) {
        return new PayoutDestinationId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static PayoutDestinationId of(UUID value) {
        return new PayoutDestinationId(value);
    }
}
