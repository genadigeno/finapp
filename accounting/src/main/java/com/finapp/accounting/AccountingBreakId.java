package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** An accounting break's identity (`P14-TSK-010`; {@code accounting V006}'s {@code accounting_break}). */
public final class AccountingBreakId extends EntityId {

    private AccountingBreakId(UUID value) {
        super(value);
    }

    public static AccountingBreakId next(IdGenerator ids) {
        return new AccountingBreakId(ids.next());
    }

    public static AccountingBreakId of(UUID value) {
        return new AccountingBreakId(value);
    }
}
