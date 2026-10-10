package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A lending-capital contribution's identity (`P11-TSK-002`; the table is `P11-TSK-004`'s). */
public final class CapitalContributionId extends EntityId {

    private CapitalContributionId(UUID value) {
        super(value);
    }

    public static CapitalContributionId next(IdGenerator ids) {
        return new CapitalContributionId(ids.next());
    }

    public static CapitalContributionId of(UUID value) {
        return new CapitalContributionId(value);
    }
}
