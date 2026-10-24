package com.finapp.app.risk;

import com.finapp.app.crossborder.CrossBorderMovements;
import com.finapp.app.crossborder.CrossBorderStepUpAdvice;
import com.finapp.crossborder.CrossBorderLimitCheck;
import com.finapp.crossborder.CrossBorderRiskDecision;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-border payments judged by risk (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108): the
 * two seams crossborder declares - {@link RiskCrossBorderRiskDecision} and {@link RiskCrossBorderLimitCheck}, one
 * {@link CrossBorderRiskJudgement} each over the one {@link RiskEvaluation} - and the door's step-up advice
 * ({@link RiskCrossBorderStepUpAdvice}). Exposed by the consumers' own types ({@code CrossBorderRiskDecision},
 * {@code CrossBorderLimitCheck}, {@code CrossBorderStepUpAdvice}), transfers' precedent ({@code RiskTransferBeans}), so
 * crossborder's composition and its customer door name no risk class ({@code NoCustomerSurfaceReadsRiskTest}).
 *
 * <p><strong>The pair is one control, wired here together</strong>: their refusals are disjoint (the authorization
 * consults risk, then limit), so the composition must never wire one without the other - which is why both live in this
 * one class and {@code CrossBorderPaymentBeans} declares neither: a missing seam fails the context, and
 * {@code RiskCrossBorderBeansTest} holds the pair to this one class.
 */
@Configuration(proxyBeanMethods = false)
public class RiskCrossBorderBeans {

    /** Crossborder's risk seam: {@code REFUSE} for a non-permitting decision no limit rule decided ({@code RiskRefused}). */
    @Bean
    CrossBorderRiskDecision<Connection> crossBorderRiskDecision(RiskEvaluation riskEvaluation,
            CrossBorderMovements crossBorderMovements) {
        return new RiskCrossBorderRiskDecision(new CrossBorderRiskJudgement(riskEvaluation, crossBorderMovements));
    }

    /** Crossborder's limit seam: {@code REFUSE} when a limit rule decided the refusal ({@code LimitRefused}). */
    @Bean
    CrossBorderLimitCheck<Connection> crossBorderLimitCheck(RiskEvaluation riskEvaluation,
            CrossBorderMovements crossBorderMovements) {
        return new RiskCrossBorderLimitCheck(new CrossBorderRiskJudgement(riskEvaluation, crossBorderMovements));
    }

    /** The cross-border door's advice before the claim: T-advise, nothing written. */
    @Bean
    CrossBorderStepUpAdvice crossBorderStepUpAdvice(RiskEvaluation riskEvaluation,
            RiskAssessmentStore riskAssessmentStore) {
        return new RiskCrossBorderStepUpAdvice(riskEvaluation, riskAssessmentStore);
    }
}
