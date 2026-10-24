package com.finapp.app.risk;

import com.finapp.app.crossborder.CrossBorderMovements;
import com.finapp.app.crossborder.CrossBorderStepUpAdvice;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskOperation;
import java.sql.Connection;
import java.util.Objects;

/**
 * The cross-border payment door's step-up advice, answered by T-advise (`P13-TSK-011`; {@code PHASE_13_PLAN.md}
 * sections 7.1, 12.3; ADR-0108 decisions 1 and 3) - {@code RiskCheckoutStepUpAdvice}'s shape: the proposed payment judged
 * exactly as the authorization's T-eval will judge it (the same request, {@link CrossBorderRiskJudgement#request}, under
 * the same command ref - the quote's id), the windows <em>peeked</em>, and {@code Advice.stepUpRequired()} answered as a
 * boolean.
 *
 * <p><strong>A judged movement is never advised.</strong> When the quote already has a sealed {@code CROSS_BORDER}
 * assessment - a payment authorised, or refused, under another key - the authorization will replay that seal, so the
 * answer is {@code false}: advising afresh would read windows that include the movement's own count and could tell the
 * customer to step up for a decision already taken ({@code INV-IDEM-01}'s reasoning, at the command ref).
 *
 * <p><strong>Writes nothing</strong> - no subject lock, no count, no row, no event. It does take the pin's
 * {@code FOR SHARE} (R1), so the door's transaction must <strong>not</strong> be {@code READ ONLY}; the door's own short
 * transaction holds that share lock for milliseconds and no money row - the lock order's risk-only shape.
 */
public final class RiskCrossBorderStepUpAdvice implements CrossBorderStepUpAdvice {

    private final RiskEvaluation evaluation;
    private final RiskAssessmentStore assessments;

    public RiskCrossBorderStepUpAdvice(RiskEvaluation evaluation, RiskAssessmentStore assessments) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
    }

    @Override
    public boolean stepUpRequired(Connection unitOfWork, CrossBorderMovements.Movement proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(proposed, "proposed");
        if (assessments.find(unitOfWork, RiskOperation.CROSS_BORDER,
                CrossBorderRiskJudgement.commandRef(proposed.quote())).isPresent()) {
            return false;
        }
        return evaluation.advise(unitOfWork, CrossBorderRiskJudgement.request(proposed)).stepUpRequired();
    }
}
