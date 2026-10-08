package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A scorecard model version's identity (`P10-TSK-011`). */
public final class ScorecardModelVersionId extends EntityId {

    private ScorecardModelVersionId(UUID value) {
        super(value);
    }

    public static ScorecardModelVersionId next(IdGenerator ids) {
        return new ScorecardModelVersionId(ids.next());
    }

    public static ScorecardModelVersionId of(UUID value) {
        return new ScorecardModelVersionId(value);
    }
}
