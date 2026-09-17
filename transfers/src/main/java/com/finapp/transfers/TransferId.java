package com.finapp.transfers;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Transfer}. Typed (ADR-0013), UUIDv7 by construction — and this is the value
 * that travels in the journal entry's reference when the transfer posts ({@code P4-TSK-005}), so
 * the movement and its accounting evidence join by identifier in both directions
 * ({@code PHASE_4_PLAN.md} §12).
 */
public final class TransferId extends EntityId {

    private TransferId(UUID value) {
        super(value);
    }

    public static TransferId next(IdGenerator ids) {
        return new TransferId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static TransferId of(UUID value) {
        return new TransferId(value);
    }
}
