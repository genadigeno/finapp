package com.finapp.kyc;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** Identifies a {@link KycDecision}. Typed, so a case identifier cannot stand in (ADR-0013). */
public final class KycDecisionId extends EntityId {

    private KycDecisionId(UUID value) {
        super(value);
    }

    public static KycDecisionId next(IdGenerator ids) {
        return new KycDecisionId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static KycDecisionId of(UUID value) {
        return new KycDecisionId(value);
    }
}
