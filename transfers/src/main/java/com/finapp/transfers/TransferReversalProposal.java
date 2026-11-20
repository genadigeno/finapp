package com.finapp.transfers;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A proposal to reverse a completed transfer, and its one decision (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135
 * decision 3: "Transfer reversal: always four-eyes"). Born {@code PROPOSED} by a holder of {@code TRANSFER_REVERSE}; decided
 * once by a holder of {@code TRANSFER_REVERSE_APPROVE} who is not the proposer - refused here and by V005's {@code CHECK} for
 * every writer ({@code INV-AUD-04}).
 */
public record TransferReversalProposal(
        TransferReversalProposalId id,
        TransferId transferId,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        ReversalProposalStatus status,
        Optional<Decision> decision) {

    /** Who decided, when, why. */
    public record Decision(String decidedBy, String decidedByType, Instant decidedAt, String reason, String correlationId) {
        public Decision {
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decidedByType, "decidedByType");
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public TransferReversalProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        if ((status == ReversalProposalStatus.PROPOSED) != decision.isEmpty()) {
            throw new IllegalArgumentException("a proposal carries a decision exactly when it is decided: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a reversal proposal is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal to reverse {@code transfer}, which must be {@code COMPLETED}. */
    public static TransferReversalProposal propose(IdGenerator ids, Clock clock, Transfer transfer, String reason,
            Actor proposer, String correlationId) {
        if (!transfer.status().canTransitionTo(TransferStatus.REVERSED)) {
            throw new TransferReversalRefusedException(TransferReversalRefusedException.Reason.NOT_REVERSIBLE,
                    "the transfer is " + transfer.status() + " and only a COMPLETED transfer can be reversed");
        }
        return new TransferReversalProposal(TransferReversalProposalId.next(ids), transfer.id(), reason, proposer.id(),
                proposer.type().name(), Instant.now(clock), correlationId, ReversalProposalStatus.PROPOSED, Optional.empty());
    }

    /**
     * This proposal decided {@code outcome} by {@code decider}: refused when decided already or when the decider proposed it.
     * Never dated before the proposal - the two acts may run on two instances whose clocks disagree.
     */
    public TransferReversalProposal decide(ReversalProposalStatus outcome, Actor decider, String reason, Clock clock,
            String correlationId) {
        if (!status.canTransitionTo(outcome)) {
            throw new TransferReversalRefusedException(TransferReversalRefusedException.Reason.ALREADY_DECIDED,
                    "the reversal proposal is " + status + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new TransferReversalRefusedException(TransferReversalRefusedException.Reason.SELF_DECISION,
                    "a reversal is decided by a person who did not propose it");
        }
        Instant now = Instant.now(clock);
        return new TransferReversalProposal(id, transferId, proposalReason, proposedBy, proposedByType, proposedAt,
                proposalCorrelationId, outcome, Optional.of(new Decision(decider.id(), decider.type().name(),
                        now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)));
    }
}
