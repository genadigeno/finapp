package com.finapp.app.risk;

import com.finapp.app.transfers.TransferStepUpAdvice;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import java.util.Objects;

/**
 * The transfer door's step-up advice, answered by T-advise (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.1, 12.3;
 * ADR-0108 decisions 1 and 3): the proposed transfer judged exactly as the command's T-eval will judge it - the same
 * request shape ({@link TransferRiskJudgement#request}), the pinned version, the windows <em>peeked</em> - and
 * {@code Advice.stepUpRequired()} answered as a boolean, so the door learns "step up" and nothing else.
 *
 * <p><strong>Writes nothing.</strong> {@code RiskEvaluation.advise} takes no subject lock, counts nothing, seals nothing
 * and announces nothing. It does take the pin's {@code FOR SHARE} (R1) - so the door's transaction must
 * <strong>not</strong> be {@code READ ONLY} (PostgreSQL refuses a row lock there, and a refused pin is the compiled
 * {@code ALLOW}: the advice would never step anyone up). The door's own short transaction holds that share lock for
 * milliseconds and no money row - the lock order's second shape, a risk-only transaction.
 *
 * <p>The command ref is a fixed token: an advice is no command and records nothing under it.
 */
public final class RiskTransferStepUpAdvice implements TransferStepUpAdvice {

    /** The advice's command ref - nothing is ever sealed under it. */
    static final CommandRef ADVICE = CommandRef.of("transfer-advice");

    private final RiskEvaluation evaluation;

    public RiskTransferStepUpAdvice(RiskEvaluation evaluation) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
    }

    @Override
    public boolean stepUpRequired(Connection unitOfWork, Proposed proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposed, "proposed");
        return evaluation.advise(unitOfWork, TransferRiskJudgement.request(ADVICE, proposed.amount(), proposed.party(),
                proposed.identity(), proposed.sourceAccount(), proposed.destinationAccount())).stepUpRequired();
    }
}
