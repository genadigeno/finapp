package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link Merchant}. Typed (ADR-0013), UUIDv7 by construction — the value the
 * merchant's payable ledger account carries as its opaque {@code owner_ref} (`V011`,
 * {@code INV-MER-02}), and the tenant every merchant-scoped statement will carry
 * ({@code INV-MER-01}, `P6-TSK-002`).
 */
public final class MerchantId extends EntityId {

    private MerchantId(UUID value) {
        super(value);
    }

    public static MerchantId next(IdGenerator ids) {
        return new MerchantId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static MerchantId of(UUID value) {
        return new MerchantId(value);
    }
}
