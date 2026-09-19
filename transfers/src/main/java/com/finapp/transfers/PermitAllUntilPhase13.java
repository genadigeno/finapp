package com.finapp.transfers;

/**
 * The Phase 4 implementation of both seams: permits everything, and is named for exactly what
 * it is rather than {@code Default...} or {@code Noop...} — a reader meeting this in wiring
 * must see at a glance that a control is deliberately absent and which phase owes it
 * (`P4-TSK-010`, `ROADMAP.md` §Refinement 2).
 *
 * <p><strong>Deliberately free of any logic and any state</strong>: the unit of work is
 * ignored, no field exists, and the seams' size is itself an assertion that no Phase 13
 * behaviour leaked early — held by test ({@code TransferSeamsTest}), not by review.
 *
 * @param <T> the transactional unit of work, ignored here — Phase 13's implementations read
 *     durable state through it (the contract on both ports)
 */
public final class PermitAllUntilPhase13<T>
        implements TransferLimitCheck<T>, TransferRiskDecision<T> {

    @Override
    public SeamVerdict check(T unitOfWork, Transfer transfer) {
        // Permit. The parameter being required is the control this phase ships.
        return SeamVerdict.PERMIT;
    }
}
