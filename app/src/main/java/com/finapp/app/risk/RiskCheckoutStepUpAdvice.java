package com.finapp.app.risk;

import com.finapp.app.checkout.CheckoutStepUpAdvice;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskOperation;
import java.sql.Connection;
import java.util.Objects;

/**
 * The checkout confirmation door's step-up advice, answered by T-advise (`P13-TSK-010`; {@code PHASE_13_PLAN.md}
 * sections 7.1, 12.3; ADR-0108 decisions 1 and 3) - {@code RiskWithdrawalStepUpAdvice}'s shape: the proposed payment
 * judged exactly as the confirmation's T-eval will judge it (the same request, {@link PaymentRiskJudgement#request}), the
 * windows <em>peeked</em>, and {@code Advice.stepUpRequired()} answered as a boolean.
 *
 * <p><strong>Writes nothing</strong> - no subject lock, no count, no row, no event. It does take the pin's
 * {@code FOR SHARE} (R1), so the door's transaction must <strong>not</strong> be {@code READ ONLY}; the door's own short
 * transaction holds that share lock for milliseconds and no money row - the lock order's risk-only shape. The command
 * ref is a fixed token: an advice is no command and nothing is sealed under it.
 */
public final class RiskCheckoutStepUpAdvice implements CheckoutStepUpAdvice {

    /** The advice's command ref - nothing is ever sealed under it. */
    static final CommandRef ADVICE = CommandRef.of("payment-advice");

    private final RiskEvaluation evaluation;

    public RiskCheckoutStepUpAdvice(RiskEvaluation evaluation) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
    }

    @Override
    public boolean stepUpRequired(Connection unitOfWork, Proposed proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposed, "proposed");
        return evaluation.advise(unitOfWork, PaymentRiskJudgement.request(
                proposed.fromWallet() ? RiskOperation.WALLET_PAYMENT : RiskOperation.CARD_PAYMENT, ADVICE,
                proposed.amount(), proposed.party(), proposed.merchant(), proposed.walletAccount()))
                .stepUpRequired();
    }
}
