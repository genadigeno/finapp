package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.CommandRef;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code WITHDRAWAL} judged by risk - the request shape the seam ({@link RiskWithdrawalRiskDecision}) and the door's
 * advice ({@link RiskWithdrawalStepUpAdvice}) share (`P13-TSK-009`; {@code PHASE_13_PLAN.md} sections 7.1, 7.3, 12.1;
 * ADR-0108), so the advice judges the proposal exactly as the command's T-eval will.
 *
 * <p><strong>The subjects.</strong> The party (whose money it is); the wallet's <strong>customer account id</strong> as
 * the source (the ledger's {@code owner_ref}, never a {@code LedgerAccountId} - `-006`); the acting identity and actor
 * when a customer acts (the actor's session answers a {@code CHALLENGE} through {@link IdentitySessionAssurance}; the
 * identity's credential change and recovery feed the {@code HOLD} rules); the flow's correlation and cause.
 *
 * <p><strong>No destination, and no device - both deliberately.</strong> The money goes to an external bank instrument,
 * which {@code RiskEvaluationRequest.Destination} cannot name (a customer account or a cross-border beneficiary - a
 * third kind is a risk migration, recorded for the coordinator); passing the payment method's id as an "account" would
 * file it under a role it is not. The only device the platform holds is identity's session label - a sanitised
 * {@code User-Agent} shared by strangers - which `-008`'s gate refused as a subject ({@code TransferRiskJudgement}).
 * The {@code DESTINATION} and {@code DEVICE} windows stay {@code ABSENT} for a withdrawal; the C8 {@code WITHDRAWAL}
 * version reads neither.
 */
public final class WithdrawalRiskJudgement {

    private WithdrawalRiskJudgement() {}

    /** A {@code WITHDRAWAL} request for {@code commandRef}: the subjects in their roles, the actor when one acts. */
    public static RiskEvaluationRequest request(
            CommandRef commandRef, Money amount, UUID party, UUID sourceAccount) {
        Objects.requireNonNull(sourceAccount, "sourceAccount");
        Correlation flow = flow();
        RiskEvaluationRequest.Builder request =
                RiskEvaluationRequest.builder(
                                RiskOperation.WITHDRAWAL,
                                commandRef,
                                amount,
                                party,
                                flow.correlationId(),
                                flow.cause().orElseThrow())
                        .sourceAccount(sourceAccount);
        SecurityContext.current().ifPresent(actor -> {
            request.actor(actor);
            identityOf(actor).ifPresent(request::identity);
        });
        return request.build();
    }

    /** A customer actor's identity - its id is the identity's ({@code SessionAuthenticationInterceptor}). */
    private static java.util.Optional<UUID> identityOf(Actor actor) {
        if (actor.type() != ActorType.CUSTOMER) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(actor.id()));
        } catch (IllegalArgumentException notAnIdentity) {
            return java.util.Optional.empty();
        }
    }

    /** The flow's correlation with its cause resolved - {@code Withdrawals}' own idiom. */
    private static Correlation flow() {
        Correlation current = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a withdrawal is judged inside a correlation scope: the"
                        + " assessment and its event carry the flow's identifier"));
        return current.cause().isPresent() ? current : current.causing(CausationId.of(current.correlationId().value()));
    }
}
