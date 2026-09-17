package com.finapp.ledger;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link Hold}. Typed (ADR-0013), UUIDv7, minted by the placing call. */
public final class HoldId extends EntityId {

    private HoldId(UUID value) {
        super(value);
    }

    public static HoldId next(IdGenerator ids) {
        return new HoldId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static HoldId of(UUID value) {
        return new HoldId(value);
    }
}
