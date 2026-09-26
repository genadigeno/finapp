package com.finapp.payments;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Withdrawal} (`P7-TSK-008`). Typed (ADR-0013), UUIDv7 by construction —
 * and the suffix of its ledger posting key, {@code wallet-withdrawal:<id>}.
 */
public final class WithdrawalId extends EntityId {

    private WithdrawalId(UUID value) {
        super(value);
    }

    public static WithdrawalId next(IdGenerator ids) {
        return new WithdrawalId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static WithdrawalId of(UUID value) {
        return new WithdrawalId(value);
    }
}
