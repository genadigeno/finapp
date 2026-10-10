package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A loan account's identity, either kind (`P11-TSK-002`; the table is `P11-TSK-012`'s). */
public final class LoanId extends EntityId {

    private LoanId(UUID value) {
        super(value);
    }

    public static LoanId next(IdGenerator ids) {
        return new LoanId(ids.next());
    }

    public static LoanId of(UUID value) {
        return new LoanId(value);
    }
}
