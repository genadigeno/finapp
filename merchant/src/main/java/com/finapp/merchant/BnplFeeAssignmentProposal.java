package com.finapp.merchant;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which fee schedule should price a merchant's BNPL product, proposed, and its one decision (`P17-TSK-116`, ADR-0154's shape;
 * finapp-admin-panel ADR-C135 decision 3: merchant fee assignment by four eyes). Born {@code PROPOSED} by a holder of
 * {@code FEE_ADMINISTER}; decided once by a holder of {@code FEE_SCHEDULE_APPROVE} who is not the proposer (refused here and
 * by merchant V021's {@code CHECK}). The approval performs {@link BnplPricing#assign}, unchanged.
 */
public record BnplFeeAssignmentProposal(
        Id id,
        MerchantId merchantId,
        MerchantBnplProduct product,
        FeeScheduleId feeScheduleId,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        Status status,
        Optional<Decision> decision) {

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

    /** {@code PROPOSED → APPROVED | REJECTED}; V021's {@code CHECK} is {@link #sqlValueList()}. */
    public enum Status {
        PROPOSED, APPROVED, REJECTED;

        public Set<Status> permittedTransitions() {
            return this == PROPOSED ? EnumSet.of(APPROVED, REJECTED) : EnumSet.noneOf(Status.class);
        }

        public static String sqlValueList() {
            return "'PROPOSED', 'APPROVED', 'REJECTED'";
        }
    }

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

    /** A proposal or decision refused by the rules: nothing written. */
    public static final class Refused extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        /** Why. */
        public enum Reason { ALREADY_PROPOSED, ALREADY_DECIDED, SELF_DECISION }

        private final Reason reason;

        public Refused(Reason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }

    public BnplFeeAssignmentProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(feeScheduleId, "feeScheduleId");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        if ((status == Status.PROPOSED) != decision.isEmpty()) {
            throw new IllegalArgumentException("a proposal carries a decision exactly when decided: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a BNPL fee assignment is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal of the assignment the door judged. */
    public static BnplFeeAssignmentProposal propose(IdGenerator ids, Clock clock, MerchantId merchantId,
            MerchantBnplProduct product, FeeScheduleId feeScheduleId, String reason, Actor proposer, String correlationId) {
        return new BnplFeeAssignmentProposal(Id.next(ids), merchantId, product, feeScheduleId, reason, proposer.id(),
                proposer.type().name(), Instant.now(clock), correlationId, Status.PROPOSED, Optional.empty());
    }

    /** Refuses {@code outcome} by {@code decider} - decided already, or the decider proposed it - before anything else. */
    public void requireDecidableBy(Status outcome, Actor decider) {
        if (!status.permittedTransitions().contains(outcome)) {
            throw new Refused(Refused.Reason.ALREADY_DECIDED, "the BNPL fee assignment proposal is " + status
                    + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new Refused(Refused.Reason.SELF_DECISION,
                    "a BNPL fee assignment proposal is decided by a person who did not propose it");
        }
    }

    /** This proposal decided {@code outcome} by {@code decider}; never dated before the proposal (N instances, N clocks). */
    public BnplFeeAssignmentProposal decide(Status outcome, Actor decider, String reason, Clock clock, String correlationId) {
        requireDecidableBy(outcome, decider);
        Instant now = Instant.now(clock);
        return new BnplFeeAssignmentProposal(id, merchantId, product, feeScheduleId, proposalReason, proposedBy,
                proposedByType, proposedAt, proposalCorrelationId, outcome, Optional.of(new Decision(decider.id(),
                        decider.type().name(), now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)));
    }
}
