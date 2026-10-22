package com.finapp.risk;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * A regulatory report's identity - the filing artefact, never the case (`P13-TSK-001`; the table is `P13-TSK-021`'s (`risk V011`)).
 */
public final class RegulatoryReportId extends EntityId {

    private RegulatoryReportId(UUID value) {
        super(value);
    }

    public static RegulatoryReportId next(IdGenerator ids) {
        return new RegulatoryReportId(ids.next());
    }

    public static RegulatoryReportId of(UUID value) {
        return new RegulatoryReportId(value);
    }
}
