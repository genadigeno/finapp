package com.finapp.merchant;

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
 * An operator payout above the four-eyes threshold, proposed, and its one decision (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135
 * decision 3). Born {@code PROPOSED} by a holder of {@code MERCHANT_PAYOUT} - the amount, the cumulative figure and the
 * threshold version it was judged against pinned on it; decided once by a holder of {@code MERCHANT_PAYOUT_APPROVE} who is not
 * the proposer (refused here and by V020's {@code CHECK}). An approval names the payout it dispatched.
 */
public record PayoutProposal(
        Id id,
        MerchantId merchantId,
        Money amount,
        int thresholdVersion,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        Status status,
        Optional<Decision> decision,
        Optional<MerchantPayoutId> payoutId) {

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

    /** {@code PROPOSED → APPROVED | REJECTED}; V020's {@code CHECK} is {@link #sqlValueList()}. */
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

    public PayoutProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(payoutId, "payoutId");
        if ((status == Status.PROPOSED) != decision.isEmpty() || (status == Status.APPROVED) != payoutId.isPresent()) {
            throw new IllegalArgumentException("a proposal carries a decision when decided, a payout when approved: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a payout proposal is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal of the payout the door judged above the threshold. */
    public static PayoutProposal propose(IdGenerator ids, Clock clock, MerchantId merchantId, Money amount,
            int thresholdVersion, String reason, Actor proposer, String correlationId) {
        return new PayoutProposal(Id.next(ids), merchantId, amount, thresholdVersion, reason, proposer.id(),
                proposer.type().name(), Instant.now(clock), correlationId, Status.PROPOSED, Optional.empty(), Optional.empty());
    }

    /**
     * This proposal decided {@code outcome} by {@code decider} - an approval names {@code payout}. Refused when decided
     * already or when the decider proposed it; never dated before the proposal (N instances, N clocks).
     */
    public PayoutProposal decide(Status outcome, Actor decider, String reason, Clock clock, String correlationId,
            Optional<MerchantPayoutId> payout) {
        if (!status.permittedTransitions().contains(outcome)) {
            throw new Refused(Refused.Reason.ALREADY_DECIDED, "the payout proposal is " + status + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new Refused(Refused.Reason.SELF_DECISION, "a payout proposal is decided by a person who did not propose it");
        }
        Instant now = Instant.now(clock);
        return new PayoutProposal(id, merchantId, amount, thresholdVersion, proposalReason, proposedBy, proposedByType,
                proposedAt, proposalCorrelationId, outcome, Optional.of(new Decision(decider.id(), decider.type().name(),
                        now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)), payout);
    }
}
