package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link KycDocument}. Typed, so a case identifier cannot stand in (ADR-0013). */
public final class DocumentId extends EntityId {

    private DocumentId(UUID value) {
        super(value);
    }

    public static DocumentId next(IdGenerator ids) {
        return new DocumentId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static DocumentId of(UUID value) {
        return new DocumentId(value);
    }
}
