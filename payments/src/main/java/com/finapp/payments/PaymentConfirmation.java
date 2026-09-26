package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The confirmation command: ADR-0046 as code ({@code P5-TSK-009}, the phase's discipline on
 * the money path for the first time).
 *
 * <h2>Dispatch-before-call, in one method's straight-line shape</h2>
 *
 * <p>{@link #confirm} is the choreography: <strong>Tx1</strong> commits
 * {@code REQUIRES_CONFIRMATION → PROCESSING} (conditional — the row count is the ten-instance
 * arbiter, {@code PHASE_5_PLAN.md} §7), the attempt at {@code AUTH_DISPATCHED} with its minted
 * {@link ProviderIdempotencyReference} ({@code INV-PAY-04}: stored before anything is sent),
 * the history rows and the person's audit record; <strong>the provider is then called holding
 * no database connection</strong> (the {@code P1-TSK-026} discipline — the call sits between
 * the two {@link TransactionRunner} blocks, which is a property of this code a test asserts
 * rather than a sentence describing it); <strong>Tx2</strong> applies the answer through
 * conditional transitions as <strong>the platform</strong> (an enumerated
 * {@code enterSystem()} site — a provider's answer has no session), retains the received bytes
 * verbatim ({@code INV-HIST-02}), and writes the outcome's audit record and event.
 *
 * <h2>Every answer is a committed state, never a guess</h2>
 *
 * <p>{@code APPROVED → AUTHORIZED}; {@code DECLINED → FAILED(DECLINED)} and the intent fails
 * with its attempt; {@code NOTHING_SENT → FAILED(PROVIDER_UNAVAILABLE)} — a connection refused
 * before send is knowledge; {@code INDETERMINATE → AUTH_UNKNOWN} ({@code INV-LIFE-03}) with
 * the intent honestly {@code PROCESSING}. An exception escaping the (total) port propagates
 * and the dispatch stays committed — <strong>a crash mid-call strands {@code AUTH_DISPATCHED},
 * visibly</strong>, which is the sweeper's subject (ADR-0046 §3), and exactly what the
 * commit-after-call inversion would destroy (the backlog's named mutation).
 *
 * <h2>A retried confirm converges and never re-dispatches</h2>
 *
 * <p>The loser of the conditional transition — a retry, a double-click, another instance —
 * reads {@code PROCESSING}, answers with the current states, and <strong>does not call the
 * provider</strong>: the stranded-dispatch case belongs to the sweeper, so a retry can never
 * cause a second provider operation ({@code INV-PAY-04} end to end). No ledger effect
 * anywhere (ADR-0048): this class imports no posting type, structurally.
 */
@RequiredArgsConstructor
public final class PaymentConfirmation {

    @NonNull private final TransactionRunner transactions;
    @NonNull private final PaymentIntentStore<java.sql.Connection> intents;
    @NonNull private final PaymentAttemptStore<java.sql.Connection> attempts;
    @NonNull private final ProviderEvidenceStore<java.sql.Connection> evidence;
    @NonNull private final PaymentParticipants<java.sql.Connection> participants;
    @NonNull private final PaymentProvider provider;
    @NonNull private final PaymentOutcomes outcomes;

    /**
     * The rail is routing's to decide, per payment (`P7-TSK-003`, ADR-0060) — the pinned
     * decision committed in Tx1 beside the attempt it governs, judged by the version in
     * force over stored inputs. Until `P7-TSK-003` a wired constant stood here; the
     * constant's javadoc said routing would take its place, and it has.
     */
    @NonNull private final RoutingStore<java.sql.Connection> routing;

    /** The build's declared rails — the capabilities eligibility judges (`INV-RAIL-01`). */
    @NonNull private final PaymentRails rails;

    @NonNull private final AuditWriter<java.sql.Connection> audit;
    @NonNull private final OutboxWriter<java.sql.Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The decision's meterable summary — counts of record stay in the rows. */
    @NonNull private final RoutingTelemetry telemetry;

    /** {@code RailSelected} (`P7-TSK-003`): the routed dispatch, published with Tx1. */
    static final String RAIL_SELECTED_EVENT_TYPE = "payments.RailSelected";

    static final int RAIL_SELECTED_EVENT_VERSION = 1;

    /**
     * What the caller learns — statuses honestly, {@code PROCESSING} included (ADR-0046).
     *
     * @param converged this call lost Tx1's conditional dispatch and never asked the provider
     * @param acting this call's own outcome transition made the committed state
     *     (`P5-TSK-017`) — false for a converged answer AND for a Tx2 that lost to a resolver
     *     which got there first, so the surface counts one judgement once
     */
    public record ConfirmationResult(
            PaymentIntentStatus intent,
            Optional<PaymentAttemptStatus> attempt,
            boolean converged,
            boolean acting) {}

    /** Tx1's yield: what the call needs, carried across the connectionless gap. */
    private record Dispatch(
            PaymentAttempt attempt,
            InstrumentToken token,
            Money amount,
            RoutingDecisionId decision) {}

    /**
     * The dispatch to perform, the converged answer, or the committed routing refusal —
     * exactly one. The refusal commits Tx1 (the recorded decision and its audit are the
     * point, ADR-0060 §3) and the command then answers {@code payments.NoEligibleRail}.
     */
    private record Tx1Outcome(
            Optional<Dispatch> dispatch,
            Optional<ConfirmationResult> converged,
            Optional<RoutingDecisionId> refused) {}

    /**
     * Confirms the caller's intent, or converges on what another confirm already did.
     *
     * @throws UnknownPaymentException nothing written — the caller's one 404
     * @throws UnknownPaymentInstrumentException the instrument was detached since creation;
     *     nothing written, the intent still awaits confirmation
     * @throws IllegalPaymentIntentTransitionException the intent is cancelled or already
     *     terminal — the caller's 409, nothing written
     * @throws NoEligibleRailException no declared rail can carry the payment (`P7-TSK-003`):
     *     the refusal IS written — a decision with no chosen rail, audited — and the intent
     *     still awaits confirmation, deliberately retryable after an operator acts
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public ConfirmationResult confirm(UUID callerPartyId, PaymentIntentId intentId) {
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        Actor person = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        // Tx1: the dispatch, committed before the provider can possibly have acted.
        Tx1Outcome tx1 =
                transactions.inTransaction(
                        uow -> dispatch(uow, callerPartyId, intentId, person, correlation));
        if (tx1.converged().isPresent()) {
            return tx1.converged().get();
        }
        if (tx1.refused().isPresent()) {
            // The refusal is durably recorded (the decision row and its audit committed with
            // Tx1); the intent still awaits confirmation, so a retry after the operator acts
            // can succeed (ADR-0060 section 3).
            throw new NoEligibleRailException(tx1.refused().get());
        }
        Dispatch dispatch = tx1.dispatch().orElseThrow();

        // The provider call - between the transactions, holding no database connection
        // (ADR-0046, P1-TSK-026). An exception here propagates: the dispatch stays committed
        // and visibly stranded, the sweeper's subject - never a fabricated outcome.
        ProviderAnswer answer =
                provider.authorize(
                        new PaymentProvider.AuthorizationRequest(
                                dispatch.attempt().authorizationReference(),
                                dispatch.token(),
                                dispatch.amount()));

        // Tx2: the outcome, applied as the platform - a provider's answer has no session
        // (PHASE_5_PLAN.md section 11's enumerated enterSystem() site).
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> applyAuthorizationOutcome(
                            uow, intentId, dispatch.attempt().id(), dispatch.amount(), answer,
                            correlation, dispatch.decision()));
        }
    }

    /** Tx1: converge or commit the dispatch — the winner is decided by one row count. */
    private Tx1Outcome dispatch(
            java.sql.Connection uow,
            UUID callerPartyId,
            PaymentIntentId intentId,
            Actor person,
            Correlation correlation) {
        PaymentIntent intent =
                intents.findOwned(uow, intentId, callerPartyId)
                        .orElseThrow(UnknownPaymentException::new);
        if (intent.status() == PaymentIntentStatus.PROCESSING) {
            return converged(uow, intent);
        }
        if (intent.status() != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
            throw new IllegalPaymentIntentTransitionException(
                    intentId, intent.status(), PaymentIntentStatus.PROCESSING);
        }

        // The instrument, re-resolved authoritatively at the act (it may have been detached
        // since creation): a refusal here throws with nothing written, the intent untouched.
        InstrumentToken token =
                participants
                        .instrumentOwnedBy(uow, callerPartyId, intent.paymentMethodId())
                        .orElseThrow(UnknownPaymentInstrumentException::new);

        // The account the capture will credit, share-locked and still open (the Phase 6 -> 7
        // transition): a customer who closed the wallet since creation must not be charged for a
        // posting the ledger will refuse. FOR SHARE serialises with the close's FOR UPDATE, and
        // the close refuses while this payment is in flight, so the two cannot interleave.
        if (!participants.creditable(uow, intent.creditAccount())) {
            throw new NoWalletForPaymentException();
        }

        // ROUTED BEFORE THE ARBITER, WRITTEN ONLY AFTER WINNING IT (P7-TSK-003, ADR-0060):
        // the losers of the conditional below converge having computed a plan and written
        // nothing, and the refusal path never contends at all. The version in force and the
        // availability facts are read in THIS transaction, so ten instances deciding in the
        // same second read the same answer and the decision records the observation it used.
        RoutingPolicyVersion policy =
                routing.findVersionInForce(uow, Instant.now(clock))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "no routing policy version is in force: V013"
                                                        + " seeds version 1, so an empty"
                                                        + " policy is a wiring fault"
                                                        + " (P7-TSK-003)"));
        RoutingInputs routingInputs =
                new RoutingInputs(
                        PaymentDirection.PAY_IN,
                        // The one confirmable instrument today is the tokenised card;
                        // BANK_ACCOUNT arrives with its own kind at its own task
                        // (P7-TSK-008), and a pay-in has no destination to reach.
                        InstrumentKind.CARD_TOKEN,
                        intent.amount(),
                        Optional.empty());
        RoutingPlan plan =
                policy.decide(routingInputs, rails, routing.availabilityByRail(uow));
        if (plan.chosen().isEmpty()) {
            return refused(uow, intentId, policy, routingInputs, plan, person, correlation);
        }

        if (!intents.transition(
                uow,
                intentId,
                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                PaymentIntentStatus.PROCESSING)) {
            // The loser: another instance confirmed (or cancelled) between the read and the
            // write. Converge on what it committed.
            PaymentIntent current =
                    intents.findById(uow, intentId).orElseThrow(UnknownPaymentException::new);
            if (current.status() == PaymentIntentStatus.PROCESSING) {
                return converged(uow, current);
            }
            throw new IllegalPaymentIntentTransitionException(
                    intentId, current.status(), PaymentIntentStatus.PROCESSING);
        }

        // The winner: the attempt is born AUTH_DISPATCHED on the CHOSEN rail, its reference
        // already minted and stored - before anything is sent (INV-PAY-04, ADR-0046).
        RailId chosen = plan.chosen().orElseThrow();
        if (rails.capabilitiesOf(chosen).interactionModel() != InteractionModel.TWO_STEP) {
            // Eligibility rejects a rail whose model cannot carry the instrument, so a
            // foreign model here means the policy, the declaration and this command
            // disagree - a wiring fault, loud before anything is written (the
            // PaymentOutcomes precedent).
            throw new IllegalStateException(
                    "routing chose '" + chosen.value() + "', whose interaction model is not"
                            + " the two-step machine this command dispatches (P7-TSK-003):"
                            + " eligibility should have refused it");
        }
        PaymentAttempt attempt =
                PaymentAttempt.create(
                        ids,
                        clock,
                        intentId,
                        chosen,
                        new ProviderIdempotencyReference("auth-" + ids.next()));
        attempts.insert(uow, attempt);

        // The decision, pinned beside the attempt it governs (INV-RAIL-02, INV-HIST-04) -
        // one transaction: the choice and the dispatch it explains cannot part ways.
        RoutingDecision decision =
                RoutingDecision.create(
                        ids, clock, intentId, policy.id(), routingInputs, plan);
        routing.insertDecision(uow, decision);
        telemetry.decided(Optional.of(chosen), Optional.empty());

        Instant now = Instant.now(clock);
        intents.recordTransition(
                uow,
                intentId,
                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                PaymentIntentStatus.PROCESSING,
                person,
                now);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        person,
                        now,
                        PaymentsAuditAction.PAYMENT_CONFIRMED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never an amount (INV-AUD-02).
                        Optional.of(
                                "intent=" + intentId
                                        + ", attempt=" + attempt.id()
                                        + ", reference="
                                        + attempt.authorizationReference().value()
                                        + ", rail=" + attempt.rail().value()
                                        + ", policyVersion=" + policy.version()
                                        + ", decision=" + decision.id())));
        // RailSelected: the routed dispatch is a decided fact, published with the
        // transaction that made it (ADR-0044's doctrine; no amount, no account identifiers).
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        RAIL_SELECTED_EVENT_TYPE,
                        RAIL_SELECTED_EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        intentId,
                        PaymentCreation.TARGET_TYPE,
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("attemptId", attempt.id().value().toString())
                        .with("decisionId", decision.id().value().toString())
                        .with("rail", chosen.value())
                        .with("policyVersion", String.valueOf(policy.version()))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Tx1Outcome(
                Optional.of(new Dispatch(attempt, token, intent.amount(), decision.id())),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * No candidate was eligible (ADR-0060 §3): record the refusal - the decision with its
     * step trail and the audit record - WITHOUT transitioning the intent, commit, and let
     * the command answer {@code payments.NoEligibleRail}. Deliberately retryable: the
     * operator re-enables a rail or ships a version, and the same confirm succeeds; the
     * one-chosen-per-intent index ignores refused rows, so the retry's decision lands.
     */
    private Tx1Outcome refused(
            java.sql.Connection uow,
            PaymentIntentId intentId,
            RoutingPolicyVersion policy,
            RoutingInputs routingInputs,
            RoutingPlan plan,
            Actor person,
            Correlation correlation) {
        RoutingDecision decision =
                RoutingDecision.create(ids, clock, intentId, policy.id(), routingInputs, plan);
        routing.insertDecision(uow, decision);
        telemetry.decided(
                Optional.empty(),
                decision.steps().stream()
                        .map(RoutingStep::rejection)
                        .flatMap(Optional::stream)
                        .findFirst());
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        person,
                        Instant.now(clock),
                        PaymentsAuditAction.PAYMENT_ROUTING_REFUSED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "intent=" + intentId
                                        + ", decision=" + decision.id()
                                        + ", policyVersion=" + policy.version()
                                        + ", steps=" + decision.steps().size())));
        return new Tx1Outcome(Optional.empty(), Optional.empty(), Optional.of(decision.id()));
    }

    /** The retry's answer: the current states, no provider call, no second dispatch. */
    private Tx1Outcome converged(java.sql.Connection uow, PaymentIntent intent) {
        Optional<PaymentAttemptStatus> attemptStatus =
                attempts.findForIntent(uow, intent.id()).map(PaymentAttempt::status);
        return new Tx1Outcome(
                Optional.empty(),
                Optional.of(
                        new ConfirmationResult(
                                intent.status(), attemptStatus, true, false)),
                Optional.empty());
    }

    /**
     * Tx2: the verbatim evidence, then the one shared outcome application
     * ({@link PaymentOutcomes} — `P5-TSK-013`'s extraction: the webhook resolver and the
     * sweeper apply the same judgement through the same code, from their own source states).
     */
    private ConfirmationResult applyAuthorizationOutcome(
            java.sql.Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            Money dispatchedAmount,
            ProviderAnswer answer,
            Correlation correlation,
            RoutingDecisionId decisionId) {
        PaymentOutcomes.Applied applied =
                outcomes.applyAuthorization(
                        uow,
                        intentId,
                        attemptId,
                        PaymentAttemptStatus.AUTH_DISPATCHED,
                        answer.verdict(),
                        answer.providerReference(),
                        // The issuer approved the dispatched ask; the promise is the
                        // dispatched amount - carried from Tx1, never re-read.
                        dispatchedAmount,
                        correlation);
        if (applied.acting() && answer.verdict() == ProviderAnswer.Verdict.NOTHING_SENT) {
            // The fallback's one lawful advance-input (INV-RAIL-02): a refused connection
            // is knowledge that nothing left. The trail records the abandonment in the same
            // transaction as the FAILED conclusion; the card-only policy has no further
            // candidate wired to a provider, so the payment concludes exactly as before and
            // the cross-rail advance arrives with the rails that can carry one
            // (P7-TSK-006, -009). An INDETERMINATE answer appends nothing, structurally -
            // the attempt stays *_UNKNOWN on its rail (INV-LIFE-03).
            RoutingDecision advanced =
                    routing.findLatestDecisionForIntent(uow, intentId)
                            .filter(decision -> decision.id().equals(decisionId))
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "the dispatched decision " + decisionId
                                                            + " is not the intent's newest:"
                                                            + " a second decision cannot"
                                                            + " exist while its dispatch is"
                                                            + " in flight (INV-RAIL-02)"))
                            .abandonedOnNothingSent();
            routing.appendStep(
                    uow, decisionId, advanced.steps().get(advanced.steps().size() - 1));
        }
        // Whatever the mapping said, what arrived is retained (INV-HIST-02) - AFTER the
        // outcome's row lock, deliberately (P5-TSK-013's lock-order rule): the evidence
        // INSERT takes FOR KEY SHARE on the attempt row, and taking it first deadlocks
        // against a concurrent resolver's key-changing UPDATE (40P01). One transaction
        // either way: retention and outcome still commit together.
        answer.evidence()
                .ifPresent(
                        bytes ->
                                evidence.append(
                                        uow,
                                        Optional.of(attemptId),
                                        Optional.empty(),
                                        EvidenceKind.RESPONSE,
                                        bytes,
                                        Instant.now(clock)));
        return new ConfirmationResult(
                applied.intent(), Optional.of(applied.attempt()), false, applied.acting());
    }
}
