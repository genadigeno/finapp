package com.finapp.merchant;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A fee schedule version, proposed, and its one decision (`P17-TSK-123`, ADR-0154's shape; finapp-admin-panel ADR-C135
 * decision 3, ADR-0125 decision 4: a price by four eyes). Born {@code PROPOSED} by a holder of {@code FEE_ADMINISTER} with
 * the terms {@link FeeSchedules#addVersion} would mint; decided once by a holder of {@code FEE_SCHEDULE_APPROVE} who is not
 * the proposer (refused here and by merchant V022's {@code CHECK}). The approval mints the version, unchanged, and names
 * it here ({@link #mintedVersion()}): the proposal explains the price.
 *
 * <p>{@code effectiveFrom} empty is "when it is approved" - {@link FeeScheduleVersion#create}'s only way to say "now",
 * resolved at the approval. A named instant already past at the approval is refused there by the effect
 * ({@code INV-MER-03}), and the proposal stays open.
 */
public record FeeScheduleVersionProposal(
        Id id,
        FeeScheduleId feeScheduleId,
        FeeRate rate,
        Money fixed,
        RoundingPolicy roundingPolicy,
        RefundFeePolicy refundFeePolicy,
        Optional<Instant> effectiveFrom,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        FeeProposalStatus status,
        Optional<FeeProposalDecision> decision,
        Optional<FeeScheduleVersionId> mintedVersion) {

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

    static final String WHAT = "fee schedule version";

    public FeeScheduleVersionProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(feeScheduleId, "feeScheduleId");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(fixed, "fixed");
        Objects.requireNonNull(roundingPolicy, "roundingPolicy");
        Objects.requireNonNull(refundFeePolicy, "refundFeePolicy");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(proposalReason, "proposalReason");
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(mintedVersion, "mintedVersion");
        if ((status == FeeProposalStatus.PROPOSED) != decision.isEmpty()) {
            throw new IllegalArgumentException("a proposal carries a decision exactly when decided: " + status);
        }
        if ((status == FeeProposalStatus.APPROVED) != mintedVersion.isPresent()) {
            throw new IllegalArgumentException("an approved version proposal names the version it minted, and only it");
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a fee schedule version is never decided by its proposer (INV-AUD-04)");
        }
    }

    /** A proposal of the terms the door judged, under {@code scheduleId}. */
    public static FeeScheduleVersionProposal propose(IdGenerator ids, Clock clock, FeeScheduleId scheduleId,
            FeeSchedules.NewVersion terms, Actor proposer, String correlationId) {
        Objects.requireNonNull(terms, "terms");
        return new FeeScheduleVersionProposal(Id.next(ids), scheduleId, terms.rate(), terms.fixed(), terms.roundingPolicy(),
                terms.refundFeePolicy(), terms.effectiveFrom(), terms.reason(), proposer.id(), proposer.type().name(),
                Instant.now(clock), correlationId, FeeProposalStatus.PROPOSED, Optional.empty(), Optional.empty());
    }

    /** The terms {@link FeeSchedules#addVersion} mints at the approval, with the proposer's reason. */
    public FeeSchedules.NewVersion terms() {
        return new FeeSchedules.NewVersion(rate, fixed, roundingPolicy, refundFeePolicy, effectiveFrom, proposalReason);
    }

    /** Refuses {@code outcome} by {@code decider} - decided already, or the decider proposed it - before anything else. */
    public void requireDecidableBy(FeeProposalStatus outcome, Actor decider) {
        FeeProposalRefused.requireDecidable(WHAT, status, proposedBy, outcome, decider);
    }

    /** This proposal approved by {@code decider}, naming the version it minted; never dated before the proposal. */
    public FeeScheduleVersionProposal approve(Actor decider, String reason, Clock clock, String correlationId,
            FeeScheduleVersionId minted) {
        Objects.requireNonNull(minted, "minted");
        return decide(FeeProposalStatus.APPROVED, decider, reason, clock, correlationId, Optional.of(minted));
    }

    /** This proposal rejected by {@code decider}: nothing minted; never dated before the proposal. */
    public FeeScheduleVersionProposal reject(Actor decider, String reason, Clock clock, String correlationId) {
        return decide(FeeProposalStatus.REJECTED, decider, reason, clock, correlationId, Optional.empty());
    }

    private FeeScheduleVersionProposal decide(FeeProposalStatus outcome, Actor decider, String reason, Clock clock,
            String correlationId, Optional<FeeScheduleVersionId> minted) {
        requireDecidableBy(outcome, decider);
        Instant now = Instant.now(clock);
        // N instances, N clocks: a decision is never dated before its proposal (V022's CHECK says so too).
        return new FeeScheduleVersionProposal(id, feeScheduleId, rate, fixed, roundingPolicy, refundFeePolicy, effectiveFrom,
                proposalReason, proposedBy, proposedByType, proposedAt, proposalCorrelationId, outcome,
                Optional.of(new FeeProposalDecision(decider.id(), decider.type().name(),
                        now.isBefore(proposedAt) ? proposedAt : now, reason, correlationId)), minted);
    }
}
