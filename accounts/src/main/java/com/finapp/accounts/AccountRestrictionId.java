package com.finapp.accounts;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A restriction's identifier (`P17-TSK-028`): a UUIDv7, minted by the application (ADR-0013). */
public final class AccountRestrictionId extends EntityId {

    private AccountRestrictionId(UUID value) {
        super(value);
    }

    public static AccountRestrictionId next(IdGenerator ids) {
        return new AccountRestrictionId(ids.next());
    }

    public static AccountRestrictionId of(UUID value) {
        return new AccountRestrictionId(value);
    }
}
