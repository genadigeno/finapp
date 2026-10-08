package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit policy version's identity (`P10-TSK-012`). */
public final class CreditPolicyVersionId extends EntityId {

    private CreditPolicyVersionId(UUID value) {
        super(value);
    }

    public static CreditPolicyVersionId next(IdGenerator ids) {
        return new CreditPolicyVersionId(ids.next());
    }

    public static CreditPolicyVersionId of(UUID value) {
        return new CreditPolicyVersionId(value);
    }
}
