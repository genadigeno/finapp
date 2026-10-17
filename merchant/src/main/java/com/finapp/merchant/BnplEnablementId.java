package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A merchant BNPL enablement's identity (`P12-TSK-006`). */
public final class BnplEnablementId extends EntityId {

    private BnplEnablementId(UUID value) {
        super(value);
    }

    public static BnplEnablementId next(IdGenerator ids) {
        return new BnplEnablementId(ids.next());
    }

    /** For values read back from storage. Validates shape, including UUIDv7. */
    public static BnplEnablementId of(UUID value) {
        return new BnplEnablementId(value);
    }
}
