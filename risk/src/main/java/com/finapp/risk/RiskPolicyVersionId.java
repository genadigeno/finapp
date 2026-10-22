package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A risk policy version's identity (`P13-TSK-001`; the table is `P13-TSK-002`'s (`risk V002`)).
 */
public final class RiskPolicyVersionId extends EntityId {

    private RiskPolicyVersionId(UUID value) {
        super(value);
    }

    public static RiskPolicyVersionId next(IdGenerator ids) {
        return new RiskPolicyVersionId(ids.next());
    }

    public static RiskPolicyVersionId of(UUID value) {
        return new RiskPolicyVersionId(value);
    }
}
