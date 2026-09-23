package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link MerchantPayout}. Typed (ADR-0013), UUIDv7 by construction — and the
 * suffix of its ledger posting key, {@code merchant-payout:<payoutId>} (ADR-0051 §2).
 */
public final class MerchantPayoutId extends EntityId {

    private MerchantPayoutId(UUID value) {
        super(value);
    }

    public static MerchantPayoutId next(IdGenerator ids) {
        return new MerchantPayoutId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static MerchantPayoutId of(UUID value) {
        return new MerchantPayoutId(value);
    }
}
