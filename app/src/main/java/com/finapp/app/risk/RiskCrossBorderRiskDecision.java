package com.finapp.app.risk;

import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.crossborder.CrossBorderRiskDecision;
import com.finapp.crossborder.CrossBorderVerdict;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import java.util.Objects;

/**
 * Crossborder's risk seam, answered by risk (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108
 * decision 3), replacing {@code PermitAllUntilPhase13} in the composition - the contract unchanged: two values, no
 * reason, judged on the authorization's own unit of work in its first transaction before the offer is accepted, a
 * refusal answered {@code 422 crossborder.RiskRefused} with nothing consumed.
 *
 * <p><strong>The mapping.</strong> {@code REFUSE} exactly when the sealed assessment does not permit the movement
 * <em>and</em> no limit rule decided the refusal: an unsatisfied {@code CHALLENGE} ({@code CHALLENGE_REQUIRED}; the door's
 * step-up advice makes it the race's answer), a {@code DENY} or {@code REVIEW} of a screening, profile or other non-limit
 * rule, a score band's (a weighted limit kind included), the fail-safe {@code DENY} (C1 - every band). {@code PERMIT} for
 * {@code ALLOW} (a recorded fail-safe {@code ALLOW} included), a satisfied {@code CHALLENGE} - and for a refusal a limit
 * rule decided ({@code Assessed.limitDecided}), which {@link RiskCrossBorderLimitCheck} refuses next.
 *
 * <p><strong>Disjoint from the limit seam, deliberately - not transfers' superset.</strong> The authorization consults
 * this seam <em>first</em> (risk, then limit - the reverse of {@code TransferExecution}); a superset here would make
 * {@code LimitRefused} unreachable. The two seams' refusals are disjoint and together are exactly every non-permitting
 * decision, so the committed code is the same in either order - {@code LimitRefused} exactly when an
 * {@code AMOUNT_BAND}/{@code VELOCITY_*} rule's own hard decision is the refusal, as on transfers - and money never moves
 * on a non-permitting decision while both are wired: they are wired together, by {@link RiskCrossBorderBeans}, in place
 * of the permit-all pair. This call evaluates and seals (the first); the limit seam's call is answered from the seal.
 */
public final class RiskCrossBorderRiskDecision implements CrossBorderRiskDecision<Connection> {

    private final CrossBorderRiskJudgement judgement;

    public RiskCrossBorderRiskDecision(CrossBorderRiskJudgement judgement) {
        this.judgement = Objects.requireNonNull(judgement, "judgement");
    }

    @Override
    public CrossBorderVerdict check(Connection unitOfWork, CrossBorderInstruction instruction) {
        return verdict(judgement.judge(unitOfWork, instruction));
    }

    /** The sealed assessment as this seam's verdict - nothing of the judgement crosses. */
    static CrossBorderVerdict verdict(RiskEvaluation.Assessed assessed) {
        return assessed.permits() || assessed.limitDecided() ? CrossBorderVerdict.PERMIT : CrossBorderVerdict.REFUSE;
    }
}
