package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A screening list version's identity (`P13-TSK-001`; the table is `P13-TSK-022`'s (`risk V012`)).
 */
public final class ScreeningListVersionId extends EntityId {

    private ScreeningListVersionId(UUID value) {
        super(value);
    }

    public static ScreeningListVersionId next(IdGenerator ids) {
        return new ScreeningListVersionId(ids.next());
    }

    public static ScreeningListVersionId of(UUID value) {
        return new ScreeningListVersionId(value);
    }
}
