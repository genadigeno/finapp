package com.finapp.credit;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A decision snapshot's identity (`P10-TSK-008`). */
public final class DecisionSnapshotId extends EntityId {

    private DecisionSnapshotId(UUID value) {
        super(value);
    }

    public static DecisionSnapshotId next(IdGenerator ids) {
        return new DecisionSnapshotId(ids.next());
    }

    public static DecisionSnapshotId of(UUID value) {
        return new DecisionSnapshotId(value);
    }
}
