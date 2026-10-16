package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A BNPL terms version's identity (`P12-TSK-002`; the table is `P12-TSK-004`'s).
 */
public final class BnplTermsVersionId extends EntityId {

    private BnplTermsVersionId(UUID value) {
        super(value);
    }

    public static BnplTermsVersionId next(IdGenerator ids) {
        return new BnplTermsVersionId(ids.next());
    }

    public static BnplTermsVersionId of(UUID value) {
        return new BnplTermsVersionId(value);
    }
}
