package com.finapp.payments;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The payments module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning ({@code P5-TSK-009}) — exactly as
 * {@code package-info}'s deliberately-few licence promised. The capture's and the refund's
 * actions arrive with theirs ({@code P5-TSK-010}/{@code -015}).
 */
@RequiredArgsConstructor
public enum PaymentsAuditAction implements AuditableAction {

    /**
     * A person asked to fund their wallet: the intent committed {@code REQUIRES_CONFIRMATION}
     * (ADR-0045 — acceptance, not execution). The record names the intent, the instrument and
     * the wallet account by identifier; the amount never appears ({@code INV-AUD-02}). Emitted
     * by the creating call only — an idempotent replay created nothing and records nothing.
     */
    PAYMENT_INTENT_CREATED(
            "payments.PaymentIntentCreated",
            "A person created a payment intent; the record names the intent, the instrument and"
                    + " the wallet account by identifier, never an amount.",
            false),

    /**
     * A person confirmed their intent: {@code PROCESSING} and the attempt's dispatch committed
     * in one transaction, before the provider is asked (ADR-0046). Emitted by the winning
     * confirm only — a converging retry moved nothing and records nothing.
     */
    PAYMENT_CONFIRMED(
            "payments.PaymentConfirmed",
            "A person confirmed a payment intent; the dispatch committed before the provider"
                    + " call, and the record names the intent and the attempt, never an amount.",
            false),

    /**
     * A person withdrew their intent from the confirmation window ({@code REQUIRES_CONFIRMATION
     * → CANCELLED} — the window ADR-0045 created for exactly this). Emitted by the winning
     * cancel only.
     */
    PAYMENT_CANCELLED(
            "payments.PaymentCancelled",
            "A person cancelled a payment intent before confirmation; nothing was dispatched"
                    + " and nothing was posted.",
            false),

    /**
     * The platform dispatched a capture for an authorized attempt ({@code P5-TSK-010}):
     * {@code AUTHORIZED → CAPTURE_DISPATCHED} committed with the minted reference, before the
     * provider is asked — ADR-0046 §1's required record of the initiation, and the initiator
     * here is the <strong>platform</strong> (the continuation of a confirmed intent has no
     * session), unlike the authorization's dispatch, which rode the person's
     * {@code PaymentConfirmed}. Emitted by the winning dispatch only.
     */
    PAYMENT_CAPTURE_DISPATCHED(
            "payments.PaymentCaptureDispatched",
            "The platform dispatched a capture for an authorized attempt; the reference was"
                    + " stored before the provider was asked, and the record names the attempt"
                    + " and the intent, never an amount.",
            false),

    /**
     * The platform applied a provider's answer to a dispatched operation — as the
     * <strong>platform</strong>, through an enumerated {@code enterSystem()} site, because a
     * provider's answer has no session ({@code PHASE_5_PLAN.md} §11). The summary carries the
     * verdict and the committed states as enumerated names; amounts and provider vocabulary
     * never appear ({@code INV-AUD-02}, {@code INV-PAY-03}).
     */
    PAYMENT_OUTCOME_APPLIED(
            "payments.PaymentOutcomeApplied",
            "The platform applied a provider outcome to a dispatched payment operation through"
                    + " a conditional transition; the record names the operation and the"
                    + " committed states, never an amount or a provider code. Acting transitions"
                    + " only: a resolver that lost the race records nothing.",
            false),

    /**
     * An operator dispatched a refund of a captured payment (`P5-TSK-015`) — the privileged,
     * reasoned act {@code INV-AUD-03} is about: the reason is <strong>required</strong> (the
     * reversal precedent), the actor is the operator holding {@code PAYMENT_REFUND}, and the
     * record commits with the {@code DISPATCHED} row and its hold in the same transaction.
     * The outcome that follows is the platform's ({@link #PAYMENT_OUTCOME_APPLIED}).
     */
    PAYMENT_REFUND_DISPATCHED(
            "payments.PaymentRefundDispatched",
            "An operator dispatched a bounded refund of a captured payment, with the required"
                    + " reason; the record names the refund, the attempt and the intent, never"
                    + " an amount.",
            true),

    /**
     * An operator created a routing policy version (`P7-TSK-003`, ADR-0060 §1) — the
     * privileged, reasoned act: how money travels changed, effective forward. The record
     * names the version and its rule count; ceilings never appear ({@code INV-AUD-02}).
     */
    PAYMENT_ROUTING_VERSION_CREATED(
            "payments.PaymentRoutingVersionCreated",
            "An operator created an immutable routing policy version, effective forward, with"
                    + " the required reason; the record names the version number and rule"
                    + " count, never a ceiling amount.",
            true),

    /**
     * An operator took a rail out of service or returned it (`P7-TSK-003`, ADR-0060 §4) —
     * the recorded fact every instance routes by. Reasoned, always: silence about why a rail
     * stopped is exactly what an incident review cannot afford.
     */
    RAIL_AVAILABILITY_CHANGED(
            "payments.RailAvailabilityChanged",
            "An operator recorded a rail as available or out of service, with the required"
                    + " reason; the record names the rail and the new state.",
            true),

    /**
     * The platform routed a payment and no declared rail could carry it (`P7-TSK-003`,
     * ADR-0060 §3): the refusal behind {@code payments.NoEligibleRail}, recorded with the
     * decision that explains it. The chosen path needs no action of its own — the winning
     * confirmation's {@link #PAYMENT_CONFIRMED} names the decision and the rail.
     */
    PAYMENT_ROUTING_REFUSED(
            "payments.PaymentRoutingRefused",
            "A payment was refused because no declared rail could carry it; the record names"
                    + " the intent, the decision, the pinned policy version and the step"
                    + " count, never an amount.",
            false),

    /**
     * An operator read a payment's routing explanation (`P7-TSK-003`) — a read of another
     * person's payment inputs under {@code PAYMENT_ROUTING_ADMINISTER}, audited like every
     * privileged read of somebody else's facts.
     */
    PAYMENT_ROUTING_EXPLANATION_READ(
            "payments.PaymentRoutingExplanationRead",
            "An operator read a payment's routing explanation; the record names the intent"
                    + " and the decision.",
            false),

    /**
     * A void was dispatched (`P7-TSK-004`): the release of an uncaptured authorization,
     * committed with its minted reference before the provider is asked ({@code INV-PAY-04}).
     * The actor is the customer withdrawing their own authorized payment, the operator (whose
     * reason is recorded verbatim), or the platform performing the declined-capture redirect;
     * the outcome that follows is the platform's ({@link #PAYMENT_OUTCOME_APPLIED}).
     */
    PAYMENT_VOID_DISPATCHED(
            "payments.PaymentVoidDispatched",
            "A void of an uncaptured authorization was dispatched; the record names the"
                    + " intent, the attempt, the reference and the rail, never an amount.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
