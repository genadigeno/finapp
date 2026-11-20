package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A refund above the four-eyes threshold, by four eyes (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decisions 3 and 4).
 * The door's refund, judged above the threshold under the attempt lock, becomes a proposal here; its approval runs the
 * existing {@link PaymentRefund} with {@link #approval} as the step inside the refund's dispatch transaction - so the
 * decision and the dispatch commit together - and its rejection moves nothing. No financial rule changes: the same hold, the
 * same refund row, the same posting at its outcome, one step later.
 *
 * <p><strong>No reservation at the proposal</strong> (ADR-0154, the owner's standing ruling of 2026-11-20): the approval's
 * refund judges the capture bound and the account's funds under its own locks on the state then; refused there, nothing is
 * written and the proposal stays open - for a later approval or a rejection.
 */
@RequiredArgsConstructor
public final class RefundProposals {

    static final String TARGET_TYPE = "payment_intent";

    @NonNull private final RefundProposalStore<Connection> proposals;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Proposes the refund the door judged above the threshold, by the acting person. */
    public RefundProposal propose(Connection unitOfWork, PaymentIntentId intentId, Money amount, String reason,
            RefundRequiresApprovalException above) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        if (proposals.findOpenFor(unitOfWork, intentId).isPresent()) {
            throw new RefundProposal.Refused(RefundProposal.Refused.Reason.ALREADY_PROPOSED,
                    "the payment already has an open refund proposal; decide it first");
        }
        RefundProposal proposed = RefundProposal.propose(ids, clock, intentId, amount, above.cumulative(),
                above.threshold().version(), reason, actor, correlation.correlationId().value());
        proposals.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                PaymentsAuditAction.REFUND_PROPOSED, TARGET_TYPE, intentId.value().toString(), Optional.of(reason),
                AuditOutcome.SUCCEEDED, correlation.correlationId(), Optional.of("proposal=" + proposed.id().value()
                        + ", thresholdVersion=" + above.threshold().version())));
        return proposed;
    }

    /** The proposal, unlocked: what its approval asks the refund command to do. */
    public Optional<RefundProposal> find(Connection unitOfWork, RefundProposal.Id id) {
        return proposals.find(unitOfWork, id);
    }

    /**
     * The approval of {@code id} as the refund command's step: inside its dispatch transaction, the proposal locked, decided
     * {@code APPROVED} by the acting person - refused when decided already or when they proposed it, which rolls the dispatch
     * back - and naming the refund the command just wrote.
     */
    public PaymentRefund.ApprovalStep approval(RefundProposal.Id id, String reason) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return (unitOfWork, refund) -> {
            Actor actor = SecurityContext.require();
            Correlation correlation = resolvedCorrelation();
            RefundProposal locked = proposals.lock(unitOfWork, id).orElseThrow(() -> new PaymentsStorageException(
                    "an approved refund proposal vanished under its own approval"));
            RefundProposal decided = locked.decide(RefundProposal.Status.APPROVED, actor, reason, clock,
                    correlation.correlationId().value(), Optional.of(refund));
            if (!proposals.decide(unitOfWork, decided)) {
                throw new PaymentsStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
            }
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                    PaymentsAuditAction.REFUND_PROPOSAL_APPROVED, TARGET_TYPE, decided.intentId().value().toString(),
                    Optional.of(reason), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                    Optional.of("proposal=" + id.value() + ", refund=" + refund.value() + ", proposedBy="
                            + decided.proposedBy())));
        };
    }

    /** Rejects the proposal: nothing moves. Empty for an unknown proposal; refused when decided or by its proposer. */
    public Optional<RefundProposal> reject(Connection unitOfWork, RefundProposal.Id id, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<RefundProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        RefundProposal decided = locked.get().decide(RefundProposal.Status.REJECTED, actor, reason, clock,
                correlation.correlationId().value(), Optional.empty());
        if (!proposals.decide(unitOfWork, decided)) {
            throw new PaymentsStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
        }
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                PaymentsAuditAction.REFUND_PROPOSAL_REJECTED, TARGET_TYPE, decided.intentId().value().toString(),
                Optional.of(reason), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                Optional.of("proposal=" + id.value() + ", proposedBy=" + decided.proposedBy())));
        return Optional.of(decided);
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current().orElseThrow(() -> new IllegalStateException(
                "a refund proposal act runs inside the request's correlation scope"));
    }
}
