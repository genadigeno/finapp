package com.finapp.app.risk;

import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.crossborder.CrossBorderLimitCheck;
import com.finapp.crossborder.CrossBorderVerdict;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import java.util.Objects;

/**
 * Crossborder's limit and velocity seam, answered by risk (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1,
 * 12.3; ADR-0108 decision 3), replacing {@code PermitAllUntilPhase13} in the composition - the contract unchanged, a
 * refusal answered {@code 422 crossborder.LimitRefused} with nothing consumed.
 *
 * <p><strong>The mapping</strong> - transfers' ({@code RiskTransferLimitCheck}): {@code REFUSE} exactly when a limit rule
 * decided the refusal - {@link RiskEvaluation.Assessed#limitDecided()}: a fired {@code AMOUNT_BAND},
 * {@code VELOCITY_COUNT} or {@code VELOCITY_SUM} rule whose own hard decision ({@code DENY} or {@code REVIEW}) is the
 * sealed decision (the EUR 10,000 band, the eleventh of the day from one account); {@code PERMIT} otherwise. An
 * unsatisfied {@code CHALLENGE}, a score band and a fail-safe are never a limit refusal - the risk seam's, which the
 * authorization consults first ({@link RiskCrossBorderRiskDecision}: the two are disjoint and together refuse every
 * non-permitting decision). A tie - the limit's own {@code DENY} beside a screening {@code DENY} - is
 * {@code LimitRefused}, as on transfers; both are the customer's generic answer (C6), and neither names the rule.
 * Answered from the seal the risk seam's call wrote, {@code replayed}: one assessment, one increment.
 */
public final class RiskCrossBorderLimitCheck implements CrossBorderLimitCheck<Connection> {

    private final CrossBorderRiskJudgement judgement;

    public RiskCrossBorderLimitCheck(CrossBorderRiskJudgement judgement) {
        this.judgement = Objects.requireNonNull(judgement, "judgement");
    }

    @Override
    public CrossBorderVerdict check(Connection unitOfWork, CrossBorderInstruction instruction) {
        return verdict(judgement.judge(unitOfWork, instruction));
    }

    /** The sealed assessment as this seam's verdict - nothing of the judgement crosses. */
    static CrossBorderVerdict verdict(RiskEvaluation.Assessed assessed) {
        return assessed.limitDecided() ? CrossBorderVerdict.REFUSE : CrossBorderVerdict.PERMIT;
    }
}
