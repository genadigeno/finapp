package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An operator's proposed resolution of a BNPL dispute above its pinned four-eyes threshold (`P12-TSK-023`; ADR-0106
 * decision 5; {@code bnpl V015}'s {@code bnpl_dispute_proposal}): {@code PROPOSED -> APPROVED} by another operator (the
 * {@code CHECK}) or {@code REJECTED} by any - its proposer's withdrawal included. Money-free; the reasons
 * {@code RESTRICTED-PII}.
 *
 * @param outcome {@code RESOLVED_REFUND} or {@code RESOLVED_UPHELD}
 * @param proposedBy {@code OPERATOR:<identity>}
 */
public record BnplDisputeProposal(
        UUID id,
        BnplDisputeId dispute,
        BnplDisputeStatus outcome,
        Status status,
        String proposedBy,
        String proposalReason,
        Optional<Instant> proposedAt,
        Optional<String> decidedBy,
        Optional<String> decisionReason,
        Optional<Instant> decidedAt,
        CorrelationId correlation) {

    /** The proposal's machine. */
    public enum Status {
        PROPOSED,
        APPROVED,
        REJECTED
    }

    public BnplDisputeProposal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (outcome != BnplDisputeStatus.RESOLVED_REFUND && outcome != BnplDisputeStatus.RESOLVED_UPHELD) {
            throw new IllegalArgumentException("a proposal proposes a refund or an uphold");
        }
        if ((status == Status.PROPOSED) != decidedBy.isEmpty()) {
            throw new IllegalArgumentException("a proposal is decided by someone exactly when it is no longer PROPOSED");
        }
        if (status == Status.APPROVED && decidedBy.get().equals(proposedBy)) {
            throw new IllegalArgumentException("a proposal is never approved by its proposer (INV-AUD-04)");
        }
    }

    /** The decision {@code to} by {@code by}, reasoned - the database stamps when. */
    BnplDisputeProposal decided(Status to, String by, String why) {
        return new BnplDisputeProposal(id, dispute, outcome, to, proposedBy, proposalReason, proposedAt, Optional.of(by),
                Optional.of(why), Optional.empty(), correlation);
    }

    /** Never a reason ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplDisputeProposal[" + id + ", " + status + "]";
    }
}
