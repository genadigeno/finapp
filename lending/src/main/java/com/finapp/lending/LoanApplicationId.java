package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A loan application's identity (`P11-TSK-002`; the table is `P11-TSK-010`'s). */
public final class LoanApplicationId extends EntityId {

    private LoanApplicationId(UUID value) {
        super(value);
    }

    public static LoanApplicationId next(IdGenerator ids) {
        return new LoanApplicationId(ids.next());
    }

    public static LoanApplicationId of(UUID value) {
        return new LoanApplicationId(value);
    }
}
