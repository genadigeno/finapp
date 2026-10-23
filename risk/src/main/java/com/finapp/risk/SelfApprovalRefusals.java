package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * The one record every risk four-eyes act makes of a refused self-approval (`P13-TSK-002`; {@code INV-RSK-07},
 * {@code INV-AUD-04}, {@code INV-AUD-01}; bnpl's {@code SelfApprovalRefusals}, risk's own): the proposer is refused,
 * nothing is written - and the attempt is recorded {@link RiskAuditAction#SELF_APPROVAL_REFUSED} {@code FAILED} on the
 * caller's unit of work, a transaction of its own after the act rolled back, so the attempt survives. Never a
 * threshold, a weight, a score or a subject in the summary ({@code INV-AUD-02}, {@code INV-RSK-08}).
 */
final class SelfApprovalRefusals {

    private SelfApprovalRefusals() {}

    /** Appends the refusal: the actor, its target (the proposal they proposed) and a summary naming it and the act. */
    static void record(AuditWriter<Connection> audit, IdGenerator ids, Clock clock, Connection unitOfWork, Actor actor,
            String targetType, String target, String summary, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_APPROVAL_REFUSED, targetType, target, Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of(summary + " refused - the approver proposed it")));
    }
}
