package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A credit evidence row's identity - and the associated data its ciphertext is bound to (`P10-TSK-006`). */
public final class CreditEvidenceId extends EntityId {

    private CreditEvidenceId(UUID value) {
        super(value);
    }

    public static CreditEvidenceId next(IdGenerator ids) {
        return new CreditEvidenceId(ids.next());
    }

    public static CreditEvidenceId of(UUID value) {
        return new CreditEvidenceId(value);
    }
}
