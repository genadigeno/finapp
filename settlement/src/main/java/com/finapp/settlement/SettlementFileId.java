package com.finapp.settlement;

import com.finapp.sharedkernel.id.EntityId;
import java.util.UUID;

/** A settlement file's identity (`P8-TSK-008`) — typed for the event envelope's aggregate. */
public final class SettlementFileId extends EntityId {

    private SettlementFileId(UUID value) {
        super(value);
    }

    public static SettlementFileId of(UUID value) {
        return new SettlementFileId(value);
    }
}
