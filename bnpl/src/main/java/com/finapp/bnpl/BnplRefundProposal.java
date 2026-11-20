package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An operator's BNPL refund above the four-eyes threshold, proposed, and its one decision (`P17-TSK-116`, ADR-0154 decision
 * 4's shape; finapp-admin-panel ADR-C135 decision 3). Born {@code PROPOSED} by a holder of {@code PAYMENT_REFUND} - the
 * amount, the cumulative figure and the threshold version it was judged against pinned on it; decided once by a holder of
 * {@code PAYMENT_REFUND_APPROVE} who is not the proposer (refused here and by V023's {@code CHECK}). An approval names the
 * refund it applied.
 */
public record BnplRefundProposal(
        Id id,
        BnplPurchaseId purchaseId,
        Money amount,
        Money cumulative,
        int thresholdVersion,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        Status status,
        Optional<Decision> decision,
        Optional<UUID> refundId) {

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

    /** {@code PROPOSED → APPROVED | REJECTED}; V023's {@code CHECK} is {@link #sqlValueList()}. */
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

    public BnplRefundProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(purchaseId, "purchaseId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(cumulative, "cumulative");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(refundId, "refundId");
        if ((status == Status.PROPOSED) != decision.isEmpty() || (status == Status.APPROVED) != refundId.isPresent()) {
            throw new IllegalArgumentException("a proposal carries a decision when decided, a refund when approved: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a BNPL refund proposal is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal of the refund the door judged above the threshold. */
    public static BnplRefundProposal propose(IdGenerator ids, Clock clock, BnplPurchaseId purchaseId, Money amount,
            Money cumulative, int thresholdVersion, String reason, Actor proposer, String correlationId) {
        return new BnplRefundProposal(Id.next(ids), purchaseId, amount, cumulative, thresholdVersion, reason, proposer.id(),
                proposer.type().name(), Instant.now(clock), correlationId, Status.PROPOSED, Optional.empty(),
                Optional.empty());
    }

    /**
     * Refuses {@code outcome} by {@code decider} before anything else is done - decided already, or the decider proposed it
     * (the approval judges this first, under the proposal's lock, so a loser of a race is answered cleanly).
     */
    public void requireDecidableBy(Status outcome, Actor decider) {
        if (!status.permittedTransitions().contains(outcome)) {
            throw new Refused(Refused.Reason.ALREADY_DECIDED, "the BNPL refund proposal is " + status + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new Refused(Refused.Reason.SELF_DECISION,
                    "a BNPL refund proposal is decided by a person who did not propose it");
        }
    }

    /**
     * This proposal decided {@code outcome} by {@code decider} - an approval names {@code refund}. Refused when decided
     * already or when the decider proposed it; never dated before the proposal (N instances, N clocks).
     */
    public BnplRefundProposal decide(Status outcome, Actor decider, String reason, Clock clock, String correlationId,
            Optional<UUID> refund) {
        requireDecidableBy(outcome, decider);
        Instant now = Instant.now(clock);
        return new BnplRefundProposal(id, purchaseId, amount, cumulative, thresholdVersion, proposalReason, proposedBy,
                proposedByType, proposedAt, proposalCorrelationId, outcome, Optional.of(new Decision(decider.id(),
                        decider.type().name(), now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)), refund);
    }
}
