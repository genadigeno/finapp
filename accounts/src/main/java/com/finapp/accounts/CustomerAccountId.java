package com.finapp.accounts;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link CustomerAccount}. Typed (ADR-0013), UUIDv7 by construction — and this is
 * the value the ledger sees as an opaque {@code owner_ref} (ADR-0042): the ledger stores it,
 * never resolves it.
 */
public final class CustomerAccountId extends EntityId {

    private CustomerAccountId(UUID value) {
        super(value);
    }

    public static CustomerAccountId next(IdGenerator ids) {
        return new CustomerAccountId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static CustomerAccountId of(UUID value) {
        return new CustomerAccountId(value);
    }
}
