package com.finapp.merchant;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A proposal to close a merchant, and its one decision (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decision 3:
 * "Merchant closure: four-eyes. It is terminal."). Born {@code PROPOSED} by a holder of {@code MERCHANT_ADMINISTER}; decided
 * once by a holder of {@code MERCHANT_CLOSE_APPROVE} who is not the proposer - refused here and by V019's {@code CHECK} for
 * every writer ({@code INV-AUD-04}).
 */
public record MerchantClosureProposal(
        MerchantClosureProposalId id,
        MerchantId merchantId,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        ClosureProposalStatus status,
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

    public MerchantClosureProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        if ((status == ClosureProposalStatus.PROPOSED) != decision.isEmpty()) {
            throw new IllegalArgumentException("a proposal carries a decision exactly when it is decided: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a closure proposal is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal to close {@code merchant}, which must be {@code ACTIVE} (the machine's one edge to {@code CLOSED}). */
    public static MerchantClosureProposal propose(IdGenerator ids, Clock clock, Merchant merchant, String reason,
            Actor proposer, String correlationId) {
        if (!merchant.status().canTransitionTo(MerchantStatus.CLOSED)) {
            throw new MerchantClosureRefusedException(MerchantClosureRefusedException.Reason.NOT_CLOSABLE,
                    "the merchant is " + merchant.status() + " and only an ACTIVE merchant can be closed");
        }
        return new MerchantClosureProposal(MerchantClosureProposalId.next(ids), merchant.id(), reason, proposer.id(),
                proposer.type().name(), Instant.now(clock), correlationId, ClosureProposalStatus.PROPOSED, Optional.empty());
    }

    /**
     * This proposal decided {@code outcome} by {@code decider}: refused when decided already or when the decider proposed it.
     * Never dated before the proposal - the two acts may run on two instances whose clocks disagree.
     */
    public MerchantClosureProposal decide(ClosureProposalStatus outcome, Actor decider, String reason, Clock clock,
            String correlationId) {
        if (!status.canTransitionTo(outcome)) {
            throw new MerchantClosureRefusedException(MerchantClosureRefusedException.Reason.ALREADY_DECIDED,
                    "the closure proposal is " + status + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new MerchantClosureRefusedException(MerchantClosureRefusedException.Reason.SELF_DECISION,
                    "a closure is decided by a person who did not propose it");
        }
        Instant now = Instant.now(clock);
        return new MerchantClosureProposal(id, merchantId, proposalReason, proposedBy, proposedByType, proposedAt,
                proposalCorrelationId, outcome, Optional.of(new Decision(decider.id(), decider.type().name(),
                        now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)));
    }
}
