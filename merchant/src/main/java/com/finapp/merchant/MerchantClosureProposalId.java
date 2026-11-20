package com.finapp.merchant;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A closure proposal's identifier (`P17-TSK-031`): a UUIDv7, minted by the application (ADR-0013). */
public final class MerchantClosureProposalId extends EntityId {

    private MerchantClosureProposalId(UUID value) {
        super(value);
    }

    public static MerchantClosureProposalId next(IdGenerator ids) {
        return new MerchantClosureProposalId(ids.next());
    }

    public static MerchantClosureProposalId of(UUID value) {
        return new MerchantClosureProposalId(value);
    }
}
