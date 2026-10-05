package com.finapp.crossborder;

/**
 * The cross-border risk seam (`P9-TSK-016`, ADR-0081 point 8; reserved in Phase 9, implemented in Phase
 * 13) - {@code transfers.TransferRiskDecision}'s contract, for the corridor.
 *
 * <p>A <strong>required parameter</strong> of the authorization, consulted in-lock in its first
 * transaction <strong>before the offer is accepted</strong>, so a {@code REFUSE} consumes nothing and
 * answers {@code 422 crossborder.RiskRefused}. Scoring, monitoring and residence are Phase 13's.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CrossBorderRiskDecision<T> {

    /** Judges {@code instruction} on the authorization's own {@code unitOfWork}. */
    CrossBorderVerdict check(T unitOfWork, CrossBorderInstruction instruction);
}
