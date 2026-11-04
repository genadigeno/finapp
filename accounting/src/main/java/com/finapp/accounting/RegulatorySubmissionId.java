package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A regulatory submission's identity (`P14-TSK-018`; {@code accounting V010}): fixed before any call, and the key the
 * provider deduplicates on ({@code INV-ACC-13}).
 */
public final class RegulatorySubmissionId extends EntityId {

    private RegulatorySubmissionId(UUID value) {
        super(value);
    }

    public static RegulatorySubmissionId next(IdGenerator ids) {
        return new RegulatorySubmissionId(ids.next());
    }

    public static RegulatorySubmissionId of(UUID value) {
        return new RegulatorySubmissionId(value);
    }
}
