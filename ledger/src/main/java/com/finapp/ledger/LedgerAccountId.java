package com.finapp.ledger;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link LedgerAccount}. Typed, so a customer-account identifier cannot stand in
 * (ADR-0013) — and here the substitution it forbids is exactly ADR-0042's boundary: the product
 * and the accounting position are different things with different identifiers.
 */
public final class LedgerAccountId extends EntityId {

    private LedgerAccountId(UUID value) {
        super(value);
    }

    public static LedgerAccountId next(IdGenerator ids) {
        return new LedgerAccountId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static LedgerAccountId of(UUID value) {
        return new LedgerAccountId(value);
    }
}
