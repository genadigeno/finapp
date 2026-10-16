package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A customer repayment obligation's identity (`P12-TSK-002`; the table is `P12-TSK-013`'s) - the {@code owner_ref} of
 * its per-obligation ledger accounts ({@code OwnerKind.BNPL_OBLIGATION}, `P12-TSK-003`).
 */
public final class BnplObligationId extends EntityId {

    private BnplObligationId(UUID value) {
        super(value);
    }

    public static BnplObligationId next(IdGenerator ids) {
        return new BnplObligationId(ids.next());
    }

    public static BnplObligationId of(UUID value) {
        return new BnplObligationId(value);
    }
}
