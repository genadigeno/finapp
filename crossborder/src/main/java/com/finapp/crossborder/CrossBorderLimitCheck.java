package com.finapp.crossborder;

/**
 * The cross-border limit and velocity seam (`P9-TSK-016`, ADR-0081 point 8; reserved in Phase 9,
 * implemented in Phase 13) - {@code transfers.TransferLimitCheck}'s contract, for the corridor.
 *
 * <p>A <strong>required parameter</strong> of the authorization, with no defaulted overload: Phase 13's
 * wiring must be a decision, and a skipped control must not compile. The authorization consults it
 * in-lock, in its first transaction, before the offer is accepted, so a {@code REFUSE} consumes nothing
 * and answers {@code 422 crossborder.LimitRefused}. Any state a verdict rests on lives in durable rows
 * read through {@code unitOfWork} - never process memory, never a cached verdict ({@code INV-CON-03}).
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CrossBorderLimitCheck<T> {

    /** Judges {@code instruction} on the authorization's own {@code unitOfWork}. */
    CrossBorderVerdict check(T unitOfWork, CrossBorderInstruction instruction);
}
