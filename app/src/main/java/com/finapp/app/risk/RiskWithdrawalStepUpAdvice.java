package com.finapp.app.risk;

import com.finapp.app.payments.WithdrawalStepUpAdvice;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import java.util.Objects;

/**
 * The withdrawal door's step-up advice, answered by T-advise (`P13-TSK-009`; {@code PHASE_13_PLAN.md} sections 7.1,
 * 12.3; ADR-0108 decisions 1 and 3) - {@code RiskTransferStepUpAdvice}'s shape: the proposed withdrawal judged exactly as
 * the command's T-eval will judge it (the same request, {@link WithdrawalRiskJudgement#request}), the windows
 * <em>peeked</em>, and {@code Advice.stepUpRequired()} answered as a boolean.
 *
 * <p><strong>Writes nothing</strong> - no subject lock, no count, no row, no event. It does take the pin's
 * {@code FOR SHARE} (R1), so the door's transaction must <strong>not</strong> be {@code READ ONLY}; the door's own short
 * transaction holds that share lock for milliseconds and no money row - the lock order's risk-only shape. The command ref
 * is a fixed token: an advice is no command and nothing is sealed under it.
 */
public final class RiskWithdrawalStepUpAdvice implements WithdrawalStepUpAdvice {

    /** The advice's command ref - nothing is ever sealed under it. */
    static final CommandRef ADVICE = CommandRef.of("withdrawal-advice");

    private final RiskEvaluation evaluation;

    public RiskWithdrawalStepUpAdvice(RiskEvaluation evaluation) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
    }

    @Override
    public boolean stepUpRequired(Connection unitOfWork, Proposed proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposed, "proposed");
        return evaluation.advise(unitOfWork, WithdrawalRiskJudgement.request(ADVICE, proposed.amount(),
                proposed.party(), proposed.sourceAccount())).stepUpRequired();
    }
}
