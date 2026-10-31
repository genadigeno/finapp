package com.finapp.accounting;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** A report run's identity (`P14-TSK-008`; {@code accounting V004}'s {@code report_run}). */
public final class ReportRunId extends EntityId {

    private ReportRunId(UUID value) {
        super(value);
    }

    public static ReportRunId next(IdGenerator ids) {
        return new ReportRunId(ids.next());
    }

    public static ReportRunId of(UUID value) {
        return new ReportRunId(value);
    }
}
