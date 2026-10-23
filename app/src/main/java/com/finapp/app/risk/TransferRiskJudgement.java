package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.Transfer;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * One transfer judged by risk - T-eval for {@code TRANSFER} (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.1, 7.3,
 * 12.1, 12.3; ADR-0108 decisions 1 and 3) - shared by the two seam adapters, {@link RiskTransferLimitCheck} and
 * {@link RiskTransferRiskDecision}.
 *
 * <p><strong>One assessment carries both answers.</strong> Each adapter calls {@link #judge} with the same transfer,
 * on the execution's own connection, inside its transaction, after {@code lockBothInFixedOrder} and the availability
 * read: the first call (the limit seam - {@code TransferExecution} consults it first) runs T-eval - the pin (R1), the
 * subjects' locks (R2a), the reads, the engine, the count (R2) when permitted, the seal (R3), {@code
 * risk.RiskDecisionMade} - and the second is answered from the seal by {@code (TRANSFER, transfer id)}, replayed:
 * no second assessment, no second increment, no second event ({@code INV-RSK-01}, {@code INV-CON-03}). The adapters
 * share nothing but this stateless object - no hand-off, no memo, no thread-local - so "one" is the database's
 * {@code UNIQUE (operation, command_ref)}, whichever seam runs first and on whichever instance.
 *
 * <p><strong>The request</strong> is built from what the seam receives and nothing a client said: the transfer's id as
 * the command ref (a canonical UUID fits the token grammar), its amount, the subjects {@link TransferSubjects} resolves
 * in-lock (the payer party, the source and destination customer account ids), the initiating identity, the acting
 * actor (whose session {@link IdentitySessionAssurance} reads), and the flow's correlation with its cause - the
 * execution's own resolution, so {@code RiskDecisionMade} joins the transfer's flow. No device ({@link #request}).
 *
 * <p><strong>What is not caught.</strong> {@code RiskEvaluation.RaceLost} - unreachable for a transfer, whose id is
 * fresh per execution and whose key the claim serialises - propagates, so the transaction rolls back with its count
 * and the retry finds the seal. A failure of the lookup or the seal aborts the transfer by {@code -004}'s design: a
 * judged transfer is never committed without its assessment.
 */
public final class TransferRiskJudgement {

    private final RiskEvaluation evaluation;
    private final TransferSubjects subjects;

    public TransferRiskJudgement(RiskEvaluation evaluation, TransferSubjects subjects) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
    }

    /** T-eval of {@code transfer} on its execution's {@code unitOfWork} - sealed once, replayed after. */
    public RiskEvaluation.Assessed judge(Connection unitOfWork, Transfer transfer) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(transfer, "transfer");
        TransferSubjects.Subjects judged = subjects.of(unitOfWork, transfer);
        return evaluation.evaluate(unitOfWork, request(CommandRef.of(transfer.id().value()), transfer.amount(),
                judged.party(), transfer.initiatedBy(), judged.sourceAccount(), judged.destinationAccount()));
    }

    /**
     * A {@code TRANSFER} request - the seam's and the door's advice's one shape: the subjects in their roles, the actor
     * when one acts, the flow's correlation.
     *
     * <p><strong>No device, deliberately</strong> (the completion gate's finding). The only device the platform holds is
     * identity's session label - a sanitised {@code User-Agent} ({@code DeviceDescription}: "Chrome on Windows"), a
     * browser build shared by every customer who runs it, not a device. Hashed into the request it would become a
     * {@code DEVICE} velocity subject that aggregates unrelated customers into one window, and - since T-eval takes one
     * advisory lock per subject the movement carries (R2a), held to the caller's commit - it would serialise every
     * transfer from one browser build, platform-wide, on one lock: under load a queue past the 250 ms budget, and then
     * the band's fail-safe ({@code DENY} from EUR 1,000) for ordinary customers. Until identity records a per-device
     * identifier, the transfer carries none: the {@code DEVICE} windows and {@code NEW_DEVICE} stay {@code ABSENT}, as
     * `-004` and `-005` already leave them.
     */
    static RiskEvaluationRequest request(CommandRef commandRef, Money amount, UUID party, UUID identity,
            UUID sourceAccount, UUID destinationAccount) {
        Correlation flow = flow();
        RiskEvaluationRequest.Builder request = RiskEvaluationRequest.builder(RiskOperation.TRANSFER, commandRef, amount,
                        party, flow.correlationId(), flow.cause().orElseThrow())
                .identity(identity)
                .sourceAccount(sourceAccount)
                .destinationAccount(destinationAccount);
        SecurityContext.current().ifPresent(request::actor);
        return request.build();
    }

    /** The flow's correlation with its cause resolved - {@code TransferExecution}'s own idiom. */
    private static Correlation flow() {
        Correlation current = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a transfer is judged inside a correlation scope: the"
                        + " assessment and its event carry the flow's identifier"));
        return current.cause().isPresent() ? current : current.causing(CausationId.of(current.correlationId().value()));
    }
}
