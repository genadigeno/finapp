package com.finapp.app.risk;

import com.finapp.transfers.SeamVerdict;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferRiskDecision;
import java.sql.Connection;
import java.util.Objects;

/**
 * Transfers' risk-decision seam, answered by risk (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3;
 * ADR-0108 decision 3), replacing {@code PermitAllUntilPhase13} in the composition - the contract unchanged, a refusal
 * committed by the execution as {@code FAILED(RISK_REFUSED)}.
 *
 * <p><strong>The mapping.</strong> {@code PERMIT} exactly when the sealed assessment permits the movement - {@code ALLOW}
 * (a recorded fail-safe {@code ALLOW} included), or a {@code CHALLENGE} the acting session satisfied - and
 * {@code REFUSE} for every other decision: an unsatisfied {@code CHALLENGE} (the assessment's refusal reason
 * {@code CHALLENGE_REQUIRED}; the door's step-up advice makes this the race's answer, not the normal path), a
 * {@code DENY} or {@code REVIEW} of any rule or band, the fail-safe {@code DENY}.
 *
 * <p><strong>A superset of the limit seam, deliberately.</strong> This seam also refuses a refusal a limit rule decided
 * - which never reaches it while {@code TransferExecution} consults the limit seam first and commits
 * {@code LIMIT_REFUSED}. Whether money moves therefore never depends on the limit seam being wired; only the committed
 * reason depends on the order. Answered from the seal the limit seam's call wrote, {@code replayed}: one assessment.
 */
public final class RiskTransferRiskDecision implements TransferRiskDecision<Connection> {

    private final TransferRiskJudgement judgement;

    public RiskTransferRiskDecision(TransferRiskJudgement judgement) {
        this.judgement = Objects.requireNonNull(judgement, "judgement");
    }

    @Override
    public SeamVerdict check(Connection unitOfWork, Transfer transfer) {
        return judgement.judge(unitOfWork, transfer).permits() ? SeamVerdict.PERMIT : SeamVerdict.REFUSE;
    }
}
