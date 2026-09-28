package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link ClearingRecord}. Typed (ADR-0013), UUIDv7 by construction — the row is
 * append-only evidence, so the id exists for foreign reference and ordering, never for
 * lifecycle.
 */
public final class ClearingRecordId extends EntityId {

    private ClearingRecordId(UUID value) {
        super(value);
    }

    public static ClearingRecordId next(IdGenerator ids) {
        return new ClearingRecordId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static ClearingRecordId of(UUID value) {
        return new ClearingRecordId(value);
    }
}
