package com.finapp.merchant;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Which fee schedule should price a merchant, proposed, and its one decision (`P17-TSK-123`, ADR-0154's shape and
 * {@link BnplFeeAssignmentProposal}'s; finapp-admin-panel ADR-C135 decision 3: merchant fee assignment by four eyes). Born
 * {@code PROPOSED} by a holder of {@code FEE_ADMINISTER}; decided once by a holder of {@code FEE_SCHEDULE_APPROVE} who is
 * not the proposer (refused here and by merchant V022's {@code CHECK}). The approval performs {@link FeeSchedules#assign},
 * unchanged.
 */
public record FeeAssignmentProposal(
        Id id,
        MerchantId merchantId,
        FeeScheduleId feeScheduleId,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        FeeProposalStatus status,
        Optional<FeeProposalDecision> decision) {

    /** The proposal's identifier: a UUIDv7 (ADR-0013). */
    public static final class Id extends EntityId {
        private Id(UUID value) {
            super(value);
        }

        public static Id next(IdGenerator ids) {
            return new Id(ids.next());
        }

        public static Id of(UUID value) {
            return new Id(value);
        }
    }

    static final String WHAT = "fee assignment";

    public FeeAssignmentProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(feeScheduleId, "feeScheduleId");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        if ((status == FeeProposalStatus.PROPOSED) != decision.isEmpty()) {
            throw new IllegalArgumentException("a proposal carries a decision exactly when decided: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a merchant fee assignment is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal of the assignment the door judged. */
    public static FeeAssignmentProposal propose(IdGenerator ids, Clock clock, MerchantId merchantId,
            FeeScheduleId feeScheduleId, String reason, Actor proposer, String correlationId) {
        return new FeeAssignmentProposal(Id.next(ids), merchantId, feeScheduleId, reason, proposer.id(),
                proposer.type().name(), Instant.now(clock), correlationId, FeeProposalStatus.PROPOSED, Optional.empty());
    }

    /** Refuses {@code outcome} by {@code decider} - decided already, or the decider proposed it - before anything else. */
    public void requireDecidableBy(FeeProposalStatus outcome, Actor decider) {
        FeeProposalRefused.requireDecidable(WHAT, status, proposedBy, outcome, decider);
    }

    /** This proposal decided {@code outcome} by {@code decider}; never dated before the proposal (N instances, N clocks). */
    public FeeAssignmentProposal decide(FeeProposalStatus outcome, Actor decider, String reason, Clock clock,
            String correlationId) {
        requireDecidableBy(outcome, decider);
        Instant now = Instant.now(clock);
        return new FeeAssignmentProposal(id, merchantId, feeScheduleId, proposalReason, proposedBy, proposedByType,
                proposedAt, proposalCorrelationId, outcome, Optional.of(new FeeProposalDecision(decider.id(),
                        decider.type().name(), now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)));
    }
}
