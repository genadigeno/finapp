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
            "A person cancelled a payment intent before confirmation - or the platform"
                    + " cancelled one a checkout session opened and never dispatched, as the"
                    + " session expired; nothing was dispatched and nothing was posted.",
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
     * The actor is the customer withdrawing their own authorized payment, or the operator
     * (whose reason is recorded verbatim); the outcome that follows is the platform's
     * ({@link #PAYMENT_OUTCOME_APPLIED}). The platform's declined-capture redirect does NOT
     * write this record: it moves the attempt into the void inside the capture outcome's own
     * transaction, recorded by that outcome's {@link #PAYMENT_OUTCOME_APPLIED} (the Phase 7
     * review corrected this javadoc; the catalogue row always said so).
     */
    PAYMENT_VOID_DISPATCHED(
            "payments.PaymentVoidDispatched",
            "A void of an uncaptured authorization was dispatched; the record names the"
                    + " intent, the attempt, the reference and the rail, never an amount.",
            false),

    /**
     * A wallet withdrawal was dispatched (`P7-TSK-008`): judged under the wallet's lock,
     * held, routed and committed with our minted reference before the scheme is asked
     * ({@code INV-PAY-04}, ADR-0062 §6). The person's own act; every outcome that follows
     * is the platform's ({@link #WITHDRAWAL_OUTCOME_APPLIED}).
     */
    WITHDRAWAL_DISPATCHED(
            "payments.WithdrawalDispatched",
            "A wallet withdrawal was dispatched; the record names the withdrawal, the"
                    + " wallet account, the instrument and the rail, never an amount.",
            false),

    /**
     * The scheme's word landed on a withdrawal (`P7-TSK-008`): applied by the dispatching
     * flight, a takeover or the inquiry sweep — the platform's act whichever resolver wins,
     * on the locked row, acting once.
     */
    WITHDRAWAL_OUTCOME_APPLIED(
            "payments.WithdrawalOutcomeApplied",
            "A withdrawal outcome was applied on the locked row; the record names the"
                    + " withdrawal, the status, the failure class and the resolver, never"
                    + " an amount or a reference.",
            false),

    /**
     * The platform applied a cross-border outbound credit's outcome on its locked row (`P9-TSK-020`):
     * the completion, a failure, an {@code UNKNOWN}, a {@code RECEIVED} or a delivery - whichever
     * resolver won (the synchronous answer, a hinted inquiry, the sweep), acting once.
     */
    OUTBOUND_CREDIT_OUTCOME_APPLIED(
            "payments.OutboundCreditOutcomeApplied",
            "An outbound credit outcome was applied on the locked row; the record names the credit,"
                    + " the status, the failure class and the resolver, never an amount or a reference.",
            false),

    /**
     * The platform parked a money-carrying confirmation that named no initiation it made
     * (`P7-TSK-009`, ADR-0062 §5, {@code INV-REC-05}): value moved on the rail with no
     * commercial home, so it rests in {@code SUSPENSE_UNMATCHED} — aged, alerted, never
     * credited by guesswork. Acting insert only; a duplicate delivery converges silently.
     */
    UNMATCHED_CONFIRMATION_PARKED(
            "payments.UnmatchedConfirmationParked",
            "The platform parked an unattributable pay-in confirmation in the unmatched"
                    + " suspense position; the record names the rail and the suspense entry,"
                    + " never an amount.",
            false),

    /**
     * The platform applied a dispute stage the card PSP notified (`P7-TSK-012`, ADR-0061 §6):
     * opening the dispute at its entry stage, or moving it along one edge of its machine —
     * one record per stage applied, so a later stage's intervening ones each stand on the
     * record. As the platform, through the webhook door's enumerated {@code enterSystem()}
     * site: an unsolicited network statement has no session. Acting only; a duplicate or
     * late delivery moves nothing and records nothing.
     */
    DISPUTE_STAGE_APPLIED(
            "payments.DisputeStageApplied",
            "The platform applied a notified dispute stage through a conditional transition;"
                    + " the record names the dispute, the attempt and the stages as the"
                    + " platform's own names, never an amount or a provider code.",
            false),

    /**
     * An operator read a dispute (`P7-TSK-012`) — somebody else's contested payment, its
     * reason and its amount, under {@code DISPUTE_ADMINISTER}: audited per dispute shown, like
     * every privileged read of another's facts ({@link #PAYMENT_ROUTING_EXPLANATION_READ}'s
     * reasoning).
     */
    DISPUTE_READ(
            "payments.DisputeRead",
            "An operator read a dispute; the record names the dispute and its attempt.",
            false),

    /**
     * The platform re-attributed part of a chargeback's excess to the payment's counterparty
     * (`P7-TSK-013`, ADR-0061 §3): an event freed headroom — a capture landing, a counted refund
     * failing, a sibling chargeback won — so the excess comes back, oldest dispute first, under
     * the attempt lock, in the freeing event's own transaction. One record per dispute moved,
     * naming its cause; the entry it posted references the dispute. *(Its description named only
     * the failed refund until `P7-TSK-014` found it stale — the P7-TSK-013 gate unified the rule
     * and this string was not re-read.)*
     */
    CHARGEBACK_REATTRIBUTED(
            "payments.ChargebackReattributed",
            "The platform re-attributed part of a chargeback's excess to the counterparty after an"
                    + " event freed headroom - a capture landing, a counted refund failing, a"
                    + " sibling chargeback won; the record names the dispute, the cause and"
                    + " where the share landed, never an amount.",
            false),

    /**
     * The platform recorded the dispute fee the card PSP reported (`P7-TSK-013`, ADR-0061 §4):
     * once per dispute ({@code NULL → value}), posted {@code DR DISPUTE_COSTS / CR} the rail's
     * clearing under {@code dispute-fee:<id>} — the platform bears it in Phase 7. As the
     * platform, through the webhook door's enumerated site.
     */
    DISPUTE_FEE_RECORDED(
            "payments.DisputeFeeRecorded",
            "The platform recorded the dispute fee the PSP reported and posted it as a dispute"
                    + " cost; the record names the dispute and its attempt, never an amount.",
            false),

    /**
     * A responder attached a document to a dispute (`P7-TSK-014`, ADR-0061 §7,
     * {@code INV-DSP-03}): the merchant over its key, or an operator for a payment with no
     * merchant, whose reason the record carries. Encrypted under the dispute-evidence key before
     * it is stored; a re-upload of the same bytes converges on the one document and says so.
     */
    DISPUTE_EVIDENCE_UPLOADED(
            "payments.DisputeEvidenceUploaded",
            "A dispute evidence document was uploaded; the record names the dispute, the"
                    + " document, its kind, its format and its size, never its content.",
            false),

    /**
     * Somebody read a dispute evidence document's content (`P7-TSK-014`, {@code INV-DSP-03} —
     * {@code INV-KYC-06}'s regime restated): the trail of who looked is the control, committed
     * with the read or neither happens. A read of a document that does not exist records nothing.
     */
    DISPUTE_EVIDENCE_READ(
            "payments.DisputeEvidenceRead",
            "A dispute evidence document's content was read; the record names the document and"
                    + " its dispute.",
            false),

    /**
     * Evidence content left the platform for the card PSP (`P7-TSK-014`): every wire send of a
     * representment's documents — the dispatching flight's (the responder's act) and every
     * re-send a takeover or the resolution sweep makes (the platform's) — committed before the
     * bytes are sent, so no transmission is off the record.
     */
    DISPUTE_EVIDENCE_TRANSMITTED(
            "payments.DisputeEvidenceTransmitted",
            "Dispute evidence was transmitted to the PSP with a response; the record names the"
                    + " response, its dispute and the documents sent, never their content.",
            false),

    /**
     * A responder answered a chargeback (`P7-TSK-014`, ADR-0061 §7): a representment or an
     * acceptance, judged under the attempt and dispute locks and committed with our minted
     * reference before the PSP is asked ({@code INV-PAY-04}). The merchant's act over its key,
     * or an operator's for a payment with no merchant, reasoned.
     */
    DISPUTE_RESPONSE_DISPATCHED(
            "payments.DisputeResponseDispatched",
            "A dispute response was dispatched; the record names the dispute, the response, its"
                    + " kind, the documents it carries and our reference.",
            false),

    /**
     * The PSP's word landed on a dispute response (`P7-TSK-014`): applied by the dispatching
     * flight, a takeover or the resolution sweep — the platform's act whichever resolver wins, on
     * the locked row, acting once. {@code SUBMITTED} means the PSP took the answer; the dispute's
     * stage stays the network's word.
     */
    DISPUTE_RESPONSE_OUTCOME_APPLIED(
            "payments.DisputeResponseOutcomeApplied",
            "A dispute response outcome was applied on the locked row; the record names the"
                    + " response, its dispute, the status and the failure class.",
            false),

    /**
     * An operator read the chargeback-ratio report (`P7-TSK-015`, {@code PHASE_7_PLAN.md} §15,
     * ADR-0018's report-not-tag rule): every merchant's card sales and chargebacks for a period,
     * across tenants, under {@code MERCHANT_ADMINISTER} — the standing judgement's evidence. One
     * record per report served, naming its period, committed with the read or neither happens.
     */
    CHARGEBACK_RATIO_READ(
            "payments.ChargebackRatioRead",
            "An operator read the chargeback-ratio report; the record names the period and how"
                    + " many credited accounts it counted, never a merchant or an amount.",
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
