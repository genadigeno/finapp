package com.finapp.transfers;

/**
 * The Phase 4 implementation of both seams: permits everything, and is named for exactly what
 * it is rather than {@code Default...} or {@code Noop...} — a reader meeting this in wiring
 * must see at a glance that a control is deliberately absent and which phase owes it
 * (`P4-TSK-010`, `ROADMAP.md` §Refinement 2). Deliberately free of any logic: the seams' size
 * is itself an assertion that no Phase 13 behaviour leaked early.
 */
public final class PermitAllUntilPhase13 implements TransferLimitCheck, TransferRiskDecision {

    @Override
    public void check(Transfer transfer) {
        // Permit. The parameter being required is the control this phase ships.
    }
}
