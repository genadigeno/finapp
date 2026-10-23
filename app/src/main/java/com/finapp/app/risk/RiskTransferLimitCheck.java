package com.finapp.app.risk;

import com.finapp.risk.RiskEvaluation;
import com.finapp.transfers.SeamVerdict;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferLimitCheck;
import java.sql.Connection;
import java.util.Objects;

/**
 * Transfers' limit seam, answered by risk (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108
 * decision 3), replacing {@code PermitAllUntilPhase13} in the composition - the contract unchanged: two values, no
 * reason, judged in-lock on the execution's own unit of work, a refusal committed by the execution as
 * {@code FAILED(LIMIT_REFUSED)}.
 *
 * <p><strong>The mapping.</strong> {@code REFUSE} exactly when a limit rule decided the refusal -
 * {@link RiskEvaluation.Assessed#limitDecided()}: a fired {@code AMOUNT_BAND}, {@code VELOCITY_COUNT} or
 * {@code VELOCITY_SUM} rule whose own hard decision ({@code DENY} or {@code REVIEW}) is the sealed decision - and
 * {@code PERMIT} for everything else, which the risk seam then judges. An unsatisfied {@code CHALLENGE} is never a
 * limit refusal ({@code CHALLENGE_REQUIRED}, the risk seam's); a score band is risk's judgement; a fail-safe has no hit.
 *
 * <p><strong>The order.</strong> {@code TransferExecution} consults this seam first, so this call runs T-eval and
 * seals the one assessment; {@link RiskTransferRiskDecision} is answered from the seal. A tie - a limit's {@code DENY}
 * beside a screening {@code DENY} - therefore commits {@code LIMIT_REFUSED}: both reasons are the customer's generic
 * answer (C6), and neither names the rule.
 */
public final class RiskTransferLimitCheck implements TransferLimitCheck<Connection> {

    private final TransferRiskJudgement judgement;

    public RiskTransferLimitCheck(TransferRiskJudgement judgement) {
        this.judgement = Objects.requireNonNull(judgement, "judgement");
    }

    @Override
    public SeamVerdict check(Connection unitOfWork, Transfer transfer) {
        return judgement.judge(unitOfWork, transfer).limitDecided() ? SeamVerdict.REFUSE : SeamVerdict.PERMIT;
    }
}
