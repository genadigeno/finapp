package com.finapp.merchant;

import java.time.Instant;
import java.util.Objects;

/** Who decided a fee proposal, when and why (`P17-TSK-123`): the five decision columns of merchant V022's rows. */
public record FeeProposalDecision(String decidedBy, String decidedByType, Instant decidedAt, String reason,
        String correlationId) {

    public FeeProposalDecision {
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedByType, "decidedByType");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
