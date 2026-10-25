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
import java.util.Optional;
import java.util.UUID;

/**
 * A {@code CARD_PAYMENT} or {@code WALLET_PAYMENT} judged by risk - the request shape the seam
 * ({@link RiskPaymentRiskDecision}) and the checkout door's advice ({@link RiskCheckoutStepUpAdvice}) share
 * (`P13-TSK-010`; {@code PHASE_13_PLAN.md} sections 7.1, 7.3, 12.1; ADR-0108), so the advice judges the proposal exactly
 * as the confirmation's T-eval will.
 *
 * <p><strong>The subjects.</strong> The payer's party; the <strong>merchant</strong> the payment credits (its id - the
 * {@code MERCHANT_PAYABLE}'s {@code owner_ref}, the checkout session's merchant) for both instruments - the
 * {@code MERCHANT_SUSPENDED} reading and the {@code MERCHANT} windows; for the wallet, the wallet's <strong>customer
 * account id</strong> as the source (the ledger's {@code owner_ref}, never a {@code LedgerAccountId} - `-006`); the acting
 * identity and actor when a customer acts (the session answers a {@code CHALLENGE} through
 * {@link IdentitySessionAssurance}); the flow's correlation and cause.
 *
 * <p><strong>No device and no destination - both deliberately.</strong> Identity holds no per-device identifier; the
 * session's label is a sanitised {@code User-Agent} shared by strangers, which `-008`'s gate refused as a subject. A card
 * is no account of the platform's, so a card payment carries no source account. The {@code DEVICE} and
 * {@code SOURCE_ACCOUNT} (card) windows stay {@code ABSENT}.
 *
 * <p><strong>The session</strong> (`P13-TSK-014`): the request's own authenticated session of the acting customer
 * ({@link ActingSession}) - by its id, never its token or its label - is the {@code SESSION} dimension's subject, so a
 * session confirming many checkouts (card testing) is counted, exactly, under R2a's lock on that one login. A system
 * flow, or a call outside a request, carries none: the {@code SESSION} windows read {@code ABSENT}.
 */
public final class PaymentRiskJudgement {

    private PaymentRiskJudgement() {}

    /** The request for {@code commandRef}: the subjects in their roles, the actor when one acts. */
    public static RiskEvaluationRequest request(
            RiskOperation operation,
            CommandRef commandRef,
            Money amount,
            UUID party,
            UUID merchant,
            Optional<UUID> sourceAccount) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(sourceAccount, "sourceAccount");
        if (operation != RiskOperation.CARD_PAYMENT && operation != RiskOperation.WALLET_PAYMENT) {
            throw new IllegalArgumentException("a checkout payment is judged as a CARD_PAYMENT or a WALLET_PAYMENT");
        }
        if ((operation == RiskOperation.WALLET_PAYMENT) != sourceAccount.isPresent()) {
            throw new IllegalArgumentException("a wallet payment names the wallet's customer account, a card payment none");
        }
        Correlation flow = flow();
        RiskEvaluationRequest.Builder request =
                RiskEvaluationRequest.builder(
                                operation,
                                commandRef,
                                amount,
                                party,
                                flow.correlationId(),
                                flow.cause().orElseThrow())
                        .merchant(merchant);
        sourceAccount.ifPresent(request::sourceAccount);
        SecurityContext.current().ifPresent(actor -> {
            request.actor(actor);
            identityOf(actor).ifPresent(request::identity);
            // P13-TSK-014: the confirming session - its id, never its token - the SESSION windows' subject.
            ActingSession.of(actor).ifPresent(session -> request.session(session.id().value()));
        });
        return request.build();
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

    /** The flow's correlation with its cause resolved - payments' own idiom. */
    private static Correlation flow() {
        Correlation current = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a payment is judged inside a correlation scope: the"
                        + " assessment and its event carry the flow's identifier"));
        return current.cause().isPresent() ? current : current.causing(CausationId.of(current.correlationId().value()));
    }
}
