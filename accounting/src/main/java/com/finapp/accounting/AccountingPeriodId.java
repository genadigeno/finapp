package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** An accounting period's identity (`P14-TSK-005`; {@code accounting V003}'s {@code accounting_period}). */
public final class AccountingPeriodId extends EntityId {

    private AccountingPeriodId(UUID value) {
        super(value);
    }

    public static AccountingPeriodId next(IdGenerator ids) {
        return new AccountingPeriodId(ids.next());
    }

    public static AccountingPeriodId of(UUID value) {
        return new AccountingPeriodId(value);
    }
}
