package com.finapp.transfers;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A reversal proposal's identifier (`P17-TSK-031`): a UUIDv7, minted by the application (ADR-0013). */
public final class TransferReversalProposalId extends EntityId {

    private TransferReversalProposalId(UUID value) {
        super(value);
    }

    public static TransferReversalProposalId next(IdGenerator ids) {
        return new TransferReversalProposalId(ids.next());
    }

    public static TransferReversalProposalId of(UUID value) {
        return new TransferReversalProposalId(value);
    }
}
