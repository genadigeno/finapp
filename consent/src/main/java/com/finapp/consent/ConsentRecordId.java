package com.finapp.consent;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link ConsentRecord}. Typed, so no other identifier can stand in (ADR-0013). */
public final class ConsentRecordId extends EntityId {

    private ConsentRecordId(UUID value) {
        super(value);
    }

    public static ConsentRecordId next(IdGenerator ids) {
        return new ConsentRecordId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static ConsentRecordId of(UUID value) {
        return new ConsentRecordId(value);
    }
}
