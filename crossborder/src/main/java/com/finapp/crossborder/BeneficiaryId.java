package com.finapp.crossborder;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A cross-border beneficiary's identifier (`P9-TSK-017`) - UUIDv7, minted by the application. */
public final class BeneficiaryId extends EntityId {

    private BeneficiaryId(UUID value) {
        super(value);
    }

    public static BeneficiaryId next(IdGenerator ids) {
        return new BeneficiaryId(ids.next());
    }

    public static BeneficiaryId of(UUID value) {
        return new BeneficiaryId(value);
    }
}
