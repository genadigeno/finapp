package com.finapp.transfers;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A transfer's reversal by four eyes (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decisions 3 and 4): a proposal is a
 * new state before the existing effect, and the approval performs that effect - {@link TransferReversal}, the same ledger
 * reversal under the same key - in the approval's own transaction. No financial rule changes.
 *
 * <p><strong>Serialization.</strong> The proposer takes the transfer {@code FOR UPDATE}, so one open proposal per transfer is
 * judged on the locked row (V005's partial unique index beneath it). The decider takes the proposal {@code FOR UPDATE}, so two
 * approvers, or an approval racing a rejection, produce one winner; the approval then reaches the transfer's lock through
 * {@link TransferReversal}. A proposer never waits on a proposal's lock, so no cycle.
 */
@RequiredArgsConstructor
public final class TransferReversalProposals {

    static final String TARGET_TYPE = TransferExecution.TARGET_TYPE;

    /** A decided proposal and the transfer's status after it. */
    public record Decided(TransferReversalProposal proposal, TransferStatus transferStatus) {}

    /** A proposal as its decider reads it (`P17-TSK-124`): the proposal and the transfer it would reverse, as they stand. */
    public record Read(TransferReversalProposal proposal, Transfer transfer) {}

    @NonNull private final TransferStore<Connection> transfers;
    @NonNull private final TransferReversalProposalStore<Connection> proposals;
    @NonNull private final TransferReversal reversal;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Proposes the transfer's reversal, by the acting person - empty for an unknown transfer. Refused when the transfer is not
     * {@code COMPLETED} or already has an open proposal; nothing is written then.
     */
    public Optional<TransferReversalProposal> propose(Connection unitOfWork, TransferId transferId, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        requireReason(reason);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<Transfer> locked = transfers.lockById(unitOfWork, transferId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        if (proposals.findOpenFor(unitOfWork, transferId).isPresent()) {
            throw new TransferReversalRefusedException(TransferReversalRefusedException.Reason.ALREADY_PROPOSED,
                    "the transfer already has an open reversal proposal; decide it first");
        }
        TransferReversalProposal proposed = TransferReversalProposal.propose(ids, clock, locked.get(), reason, actor,
                correlation.correlationId().value());
        proposals.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                TransfersAuditAction.REVERSAL_PROPOSED, TARGET_TYPE, transferId.value().toString(), Optional.of(reason),
                AuditOutcome.SUCCEEDED, correlation.correlationId(),
                Optional.of("proposal=" + proposed.id().value() + ", customer=" + locked.get().customerId())));
        return Optional.of(proposed);
    }

    /**
     * The proposal and its transfer, unlocked - what its decider reads before deciding (`P17-TSK-124`). Empty for an unknown
     * proposal. Nothing is written here: the caller records the serving in this unit of work.
     */
    public Optional<Read> read(Connection unitOfWork, TransferReversalProposalId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return proposals.find(unitOfWork, id).map(proposal -> new Read(proposal, transfers.findById(unitOfWork,
                proposal.transferId()).orElseThrow(() -> new TransfersStorageException(
                        "a reversal proposal names a transfer that does not exist"))));
    }

    /**
     * Approves the proposal: the transfer is reversed in this transaction, by {@link TransferReversal} under the approver -
     * whose reason its {@code transfers.TransferReversed} record carries. Empty for an unknown proposal. Refused when decided
     * already, when the approver proposed it, or when the transfer is no longer {@code COMPLETED}; nothing is written then.
     */
    public Optional<Decided> approve(Connection unitOfWork, TransferReversalProposalId id, String reason) {
        return decide(unitOfWork, id, ReversalProposalStatus.APPROVED, reason);
    }

    /** Rejects the proposal: nothing moves. Empty for an unknown proposal; refused as {@link #approve}, never for the transfer. */
    public Optional<Decided> reject(Connection unitOfWork, TransferReversalProposalId id, String reason) {
        return decide(unitOfWork, id, ReversalProposalStatus.REJECTED, reason);
    }

    private Optional<Decided> decide(Connection unitOfWork, TransferReversalProposalId id, ReversalProposalStatus outcome,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        requireReason(reason);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<TransferReversalProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        TransferReversalProposal decided = locked.get().decide(outcome, actor, reason, clock,
                correlation.correlationId().value());
        TransferStatus after;
        if (outcome == ReversalProposalStatus.APPROVED) {
            try {
                after = reversal.reverse(unitOfWork, decided.transferId(), reason)
                        .orElseThrow(() -> new TransfersStorageException("a reversal proposal names a transfer that does not"
                                + " exist"))
                        .status();
            } catch (IllegalTransferTransitionException notCompleted) {
                throw new TransferReversalRefusedException(TransferReversalRefusedException.Reason.NOT_REVERSIBLE,
                        "the transfer is " + notCompleted.from() + " and only a COMPLETED transfer can be reversed");
            }
        } else {
            after = transfers.findById(unitOfWork, decided.transferId())
                    .orElseThrow(() -> new TransfersStorageException("a reversal proposal names a transfer that does not"
                            + " exist"))
                    .status();
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                    TransfersAuditAction.REVERSAL_REJECTED, TARGET_TYPE, decided.transferId().value().toString(),
                    Optional.of(reason), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                    Optional.of("proposal=" + decided.id().value() + ", proposedBy=" + decided.proposedBy())));
        }
        if (!proposals.decide(unitOfWork, decided)) {
            throw new TransfersStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
        }
        return Optional.of(new Decided(decided, after));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            // The boundary refuses this with a 422 first; here it is an internal caller defect.
            throw new IllegalArgumentException("a reversal act requires a reason of 1 to 1000 characters");
        }
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current().orElseThrow(() -> new IllegalStateException(
                "a reversal act runs inside the request's correlation scope"));
    }
}
