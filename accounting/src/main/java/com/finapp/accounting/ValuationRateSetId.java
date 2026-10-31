package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A valuation rate set's identity (`P14-TSK-014`; {@code accounting V005}'s {@code valuation_rate_set}). */
public final class ValuationRateSetId extends EntityId {

    private ValuationRateSetId(UUID value) {
        super(value);
    }

    public static ValuationRateSetId next(IdGenerator ids) {
        return new ValuationRateSetId(ids.next());
    }

    public static ValuationRateSetId of(UUID value) {
        return new ValuationRateSetId(value);
    }
}
