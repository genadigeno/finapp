package com.finapp.ledger;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies an {@link AdjustmentProposal}. Typed (ADR-0013), UUIDv7, minted by the
 * proposing call.
 */
public final class AdjustmentProposalId extends EntityId {

    private AdjustmentProposalId(UUID value) {
        super(value);
    }

    public static AdjustmentProposalId next(IdGenerator ids) {
        return new AdjustmentProposalId(ids.next());
    }

    /** For values read back from storage or a URL. Validates shape, including UUIDv7. */
    public static AdjustmentProposalId of(UUID value) {
        return new AdjustmentProposalId(value);
    }
}
