package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A loan product terms version's identity (`P11-TSK-002`; the table is `P11-TSK-005`'s). */
public final class LoanTermsVersionId extends EntityId {

    private LoanTermsVersionId(UUID value) {
        super(value);
    }

    public static LoanTermsVersionId next(IdGenerator ids) {
        return new LoanTermsVersionId(ids.next());
    }

    public static LoanTermsVersionId of(UUID value) {
        return new LoanTermsVersionId(value);
    }
}
