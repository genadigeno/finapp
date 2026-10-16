package com.finapp.lending;

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
 * The one record every lending four-eyes act makes of a refused self-approval (the Phase 11 completion gate's R4;
 * {@code INV-AUD-04}, {@code INV-AUD-01}): a proposer who tried to approve their own repayment reversal, waiver,
 * amendment, terms version or capital contribution is refused, nothing is written - and the attempt is recorded
 * {@link LendingAuditAction#SELF_APPROVAL_REFUSED} {@code FAILED} on the caller's unit of work, a transaction of its own
 * after the act rolled back, so the attempt survives (as {@code lending.SelfDealingRefused} is recorded; credit's
 * {@code REVIEW_SECOND_APPROVAL_REFUSED} and merchant's {@code PayoutDestinationApprovalRefused} precedents). Never an
 * amount, a rate or a reason in the summary ({@code INV-AUD-02}).
 */
final class SelfApprovalRefusals {

    private SelfApprovalRefusals() {}

    /**
     * Appends the refusal: the actor, its target ({@code targetType}/{@code target} - the proposal they proposed) and a
     * summary naming the proposal and the act.
     */
    static void record(AuditWriter<Connection> audit, IdGenerator ids, Clock clock, Connection unitOfWork, Actor actor,
            String targetType, String target, String summary, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.SELF_APPROVAL_REFUSED, targetType, target, Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of(summary + " refused - the approver proposed it")));
    }
}
