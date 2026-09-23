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
    @NonNull private final AuditWriter<java.sql.Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

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
    private record Dispatch(PaymentAttempt attempt, InstrumentToken token, Money amount) {}

    /** Either the dispatch to perform, or the converged answer. */
    private record Tx1Outcome(Optional<Dispatch> dispatch, Optional<ConfirmationResult> converged) {}

    /**
     * Confirms the caller's intent, or converges on what another confirm already did.
     *
     * @throws UnknownPaymentException nothing written — the caller's one 404
     * @throws UnknownPaymentInstrumentException the instrument was detached since creation;
     *     nothing written, the intent still awaits confirmation
     * @throws IllegalPaymentIntentTransitionException the intent is cancelled or already
     *     terminal — the caller's 409, nothing written
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
                            correlation));
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

        // The winner: the attempt is born AUTH_DISPATCHED with its reference already minted
        // and stored - before anything is sent (INV-PAY-04, ADR-0046).
        PaymentAttempt attempt =
                PaymentAttempt.create(
                        ids,
                        clock,
                        intentId,
                        new ProviderIdempotencyReference("auth-" + ids.next()));
        attempts.insert(uow, attempt);

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
                                        + attempt.authorizationReference().value())));
        return new Tx1Outcome(
                Optional.of(new Dispatch(attempt, token, intent.amount())), Optional.empty());
    }

    /** The retry's answer: the current states, no provider call, no second dispatch. */
    private Tx1Outcome converged(java.sql.Connection uow, PaymentIntent intent) {
        Optional<PaymentAttemptStatus> attemptStatus =
                attempts.findForIntent(uow, intent.id()).map(PaymentAttempt::status);
        return new Tx1Outcome(
                Optional.empty(),
                Optional.of(
                        new ConfirmationResult(
                                intent.status(), attemptStatus, true, false)));
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
            Correlation correlation) {
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
