package com.finapp.app.risk;

import com.finapp.app.crossborder.CrossBorderMovements;
import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One cross-border payment judged by risk - T-eval for {@code CROSS_BORDER} (`P13-TSK-011`; {@code PHASE_13_PLAN.md}
 * sections 7.2, 7.3, 12.1, 12.3; ADR-0108 decisions 1 and 3) - shared by the two seam adapters,
 * {@link RiskCrossBorderRiskDecision} and {@link RiskCrossBorderLimitCheck}, and (its request shape) by the door's advice
 * {@link RiskCrossBorderStepUpAdvice}.
 *
 * <p><strong>One assessment carries both answers.</strong> Each adapter calls {@link #judge} with the same instruction,
 * on the authorization's own connection, inside its first transaction: the first call (the risk seam - the authorization
 * consults it first) takes the movement's money rows ({@link CrossBorderMovements#locked}: the quote, then the source
 * wallet, Phase 9's order) and runs T-eval - the pin (R1), the subjects' locks (R2a), the reads, the engine, the count (R2)
 * when permitted, the seal (R3), {@code risk.RiskDecisionMade} - and the second is answered from the seal by
 * {@code (CROSS_BORDER, quote id)}, replayed. The adapters share nothing but this stateless object.
 *
 * <p><strong>The command ref is the quote's id</strong>: the movement's identity before the payment's id exists (minted
 * after the seams), one payment per quote ({@code payment_one_per_quote}) - so one movement has one assessment however
 * many keys authorise it, and a retry under any key replays the sealed decision; a refused quote stays refused (the
 * remedy a new quote, as checkout's refused intent). Two keys on one quote are serialised at the quote's row lock before
 * the lookup, so {@code RiskEvaluation.RaceLost} is unreachable here; met, it propagates, rolling the transaction back
 * with its count.
 *
 * <p><strong>The judged amount is the movement's EUR figure</strong> ({@link #judgedAmount}; `P13-TSK-003`'s follow-up:
 * the engine compares no currencies, every version being in {@link RiskPolicy#REFERENCE_CURRENCY}).
 */
public final class CrossBorderRiskJudgement {

    private final RiskEvaluation evaluation;
    private final CrossBorderMovements movements;

    public CrossBorderRiskJudgement(RiskEvaluation evaluation, CrossBorderMovements movements) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
        this.movements = Objects.requireNonNull(movements, "movements");
    }

    /** T-eval of {@code instruction} on its authorization's {@code unitOfWork} - sealed once, replayed after. */
    public RiskEvaluation.Assessed judge(Connection unitOfWork, CrossBorderInstruction instruction) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(instruction, "instruction");
        CrossBorderMovements.Movement movement = movements.locked(unitOfWork, instruction);
        if (!movement.quote().equals(instruction.quoteId()) || !movement.party().equals(instruction.customerId())
                || !movement.corridor().equals(instruction.corridor())) {
            throw new IllegalStateException("the movement read is not the instruction's - an invariant is broken");
        }
        return evaluation.evaluate(unitOfWork, request(movement));
    }

    /** The command ref of {@code quote}'s movement. */
    static CommandRef commandRef(UUID quote) {
        return CommandRef.of(quote);
    }

    /**
     * A {@code CROSS_BORDER} request - the seam's and the door's advice's one shape: the quote's id as the command ref,
     * the EUR judged amount, the payer party, the acting identity and actor when a customer acts (the actor's session
     * answers a {@code CHALLENGE} through {@link IdentitySessionAssurance}), the source wallet's customer account when
     * one is debited, the beneficiary as the destination (the {@code DESTINATION} windows, {@code NEW_PAYEE}'s
     * registration age), the corridor's {@code SRC-DST-CC} ({@code CORRIDOR_HIGH_RISK}), the flow's correlation.
     *
     * <p><strong>No device</strong> - `-008`'s finding: the only device the platform holds is identity's session label, a
     * browser build shared by strangers. <strong>No merchant</strong>: a corridor payment pays a beneficiary.
     */
    static RiskEvaluationRequest request(CrossBorderMovements.Movement movement) {
        Correlation flow = flow();
        RiskEvaluationRequest.Builder request = RiskEvaluationRequest.builder(RiskOperation.CROSS_BORDER,
                        commandRef(movement.quote()), judgedAmount(movement.source(), movement.destination()),
                        movement.party(), flow.correlationId(), flow.cause().orElseThrow())
                .destinationBeneficiary(movement.beneficiary())
                .corridor(movement.corridor().code());
        movement.sourceAccount().ifPresent(request::sourceAccount);
        SecurityContext.current().ifPresent(actor -> {
            request.actor(actor);
            identityOf(actor).ifPresent(request::identity);
        });
        return request.build();
    }

    /**
     * The movement's figure in the policy's currency (`P13-TSK-003`'s follow-up): the quote's <strong>source</strong>
     * amount when the corridor's source is EUR; else its <strong>destination</strong> amount when the corridor converts
     * to EUR; else the source amount as it is - which the engine refuses ({@code CURRENCY_MISMATCH}), so the movement is
     * decided by the recorded fail-safe ({@code RISK_UNAVAILABLE}, the open-ended band: {@code DENY} for cross-border,
     * C1) - the owner item for a corridor with no EUR leg.
     *
     * <p>Both EUR figures are the quote's principal - what the customer pays before crossborder's corridor fee, or what
     * the beneficiary receives - so one measure is judged on every corridor: a EUR-destination figure has no fee in it,
     * and the fee (crossborder's charge, not the movement) is judged nowhere.
     */
    static Money judgedAmount(Money source, Money destination) {
        if (source.currency().equals(RiskPolicy.REFERENCE_CURRENCY)) {
            return source;
        }
        if (destination.currency().equals(RiskPolicy.REFERENCE_CURRENCY)) {
            return destination;
        }
        return source;
    }

    /** A customer actor's identity - its id is the identity's ({@code SessionAuthenticationInterceptor}). */
    private static Optional<UUID> identityOf(Actor actor) {
        if (actor.type() != ActorType.CUSTOMER) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(actor.id()));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }

    /** The flow's correlation with its cause resolved - the authorization's own idiom. */
    private static Correlation flow() {
        Correlation current = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a cross-border payment is judged inside a correlation"
                        + " scope: the assessment and its event carry the flow's identifier"));
        return current.cause().isPresent() ? current : current.causing(CausationId.of(current.correlationId().value()));
    }
}
