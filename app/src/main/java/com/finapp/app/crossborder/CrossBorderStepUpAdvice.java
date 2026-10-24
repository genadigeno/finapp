package com.finapp.app.crossborder;

import java.sql.Connection;

/**
 * The cross-border payment door's step-up advice (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 7.1, 12.3; ADR-0108
 * decision 3): would the proposed payment be challenged, and does the acting session fail to satisfy the challenge? An
 * {@code app} port, deliberately - the transfer's ({@code TransferStepUpAdvice}), the withdrawal's and the checkout's
 * shape: the customer's cross-border door asks a yes-or-no question in identity's step-up vocabulary and names no type of
 * {@code risk}'s ({@code INV-RSK-08}, {@code NoCustomerSurfaceReadsRiskTest}); {@code app}'s risk composition implements
 * it over T-advise.
 *
 * <p><strong>Advice, never authority.</strong> Asked before the claim, in the door's own short transaction that writes
 * nothing; the authorization's T-eval in its first transaction decides. A {@code true} here is the door's
 * {@code 403 identity.AssuranceRequired} before any row exists; a judgement that disagrees later (the windows moved, the
 * session was revoked) is the authorization's {@code 422 crossborder.RiskRefused} - the race's answer.
 */
@FunctionalInterface
public interface CrossBorderStepUpAdvice {

    /** Nothing ever requires a step-up - the composition without a risk judgement. */
    CrossBorderStepUpAdvice NEVER = (unitOfWork, proposed) -> false;

    /**
     * True when the proposed payment - the caller's own offer and wallet, as {@link CrossBorderMovements#owned} resolved
     * them - needs a step-up the acting session does not hold. Writes nothing.
     */
    boolean stepUpRequired(Connection unitOfWork, CrossBorderMovements.Movement proposed);
}
