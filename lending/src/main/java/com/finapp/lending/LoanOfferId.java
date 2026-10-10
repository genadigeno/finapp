package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A loan offer's identity (`P11-TSK-002`; the table is `P11-TSK-011`'s). */
public final class LoanOfferId extends EntityId {

    private LoanOfferId(UUID value) {
        super(value);
    }

    public static LoanOfferId next(IdGenerator ids) {
        return new LoanOfferId(ids.next());
    }

    public static LoanOfferId of(UUID value) {
        return new LoanOfferId(value);
    }
}
