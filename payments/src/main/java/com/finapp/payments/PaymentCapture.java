package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
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
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The capture command: the ledger's first touch ({@code P5-TSK-010}, ADR-0048) — the same
 * dispatch-before-call choreography as the confirmation, with the one thing that is genuinely
 * new held at its centre: <strong>the {@code CAPTURED} transition, the posting and the
 * intent's {@code SUCCEEDED} are one transaction</strong>. A {@code CAPTURED} state beside no
 * journal entry is money that vanished; an entry beside no {@code CAPTURED} is money nobody
 * admits taking; this class makes both unrepresentable by writing them on one connection.
 *
 * <h2>The posting (ADR-0048)</h2>
 *
 * <p>DR the clearing account / CR the customer wallet, key
 * {@code payment-capture:<attemptId>}, entry reference the attempt id — so the §12 chain
 * (intent → attempt → provider references → journal entry → wallet balance) is walkable by
 * stored identifier in both directions, and a duplicate outcome from any future resolver is
 * structurally unable to post twice ({@code INV-IDEM-01}'s kernel, no new mechanism).
 *
 * <p><strong>"{@code PSP_CLEARING}" is the chart's {@code SETTLEMENT_CLEARING}</strong>: the
 * payment documents' name resolves to the operational account {@code P3-TSK-003} actually
 * seeded per currency ({@code ASSET}, "value in flight, due from an external counterparty") —
 * one account, two vocabularies, and its balance is continuously the captured-but-unsettled
 * position ({@code PAYMENT_LIFECYCLES.md} §5). A second clearing purpose would split that
 * position. Captured is <strong>not settled</strong> ({@code INV-SET-01}): nothing here or
 * anywhere in Phase 5 moves clearing onward — that is Phase 8's file-driven fact.
 *
 * <h2>No savepoint around the posting — deliberately the inverse of the transfer</h2>
 *
 * <p>A transfer's posting refusal becomes a committed {@code FAILED}; a capture's posting
 * failure must fail the whole outcome transaction <strong>loudly</strong>: the provider HAS
 * captured, so committing any state that hides the un-posted money is worse than a stranded
 * {@code CAPTURE_DISPATCHED} an operator and the sweeper can see. The rollback leaves no
 * posting and no transition — the acceptance's own clause, and what the posting-hoisted-out
 * mutation destroys.
 *
 * <h2>The platform's act, end to end</h2>
 *
 * <p>Capture is the continuation of a confirmed intent — chained by the surface after a
 * synchronous {@code AUTHORIZED} ({@code P5-TSK-011}) or by a resolver ({@code P5-TSK-013}/
 * {@code -014}) — and has no session either way: one enumerated {@code enterSystem()} site
 * wraps the whole command ({@code PHASE_5_PLAN.md} §11). Ambiguity commits
 * {@code CAPTURE_UNKNOWN} with <strong>nothing posted</strong> ({@code INV-LIFE-03});
 * {@code DECLINED} and a refused connection fail the attempt and the intent with it (no retry
 * policy until Phase 7 — ADR-0045 §4's recorded austerity); a crash mid-call strands
 * {@code CAPTURE_DISPATCHED} visibly, and a retried capture converges with zero provider
 * calls.
 */
public final class PaymentCapture {

    static final String CAPTURED_EVENT_TYPE = "payments.PaymentCaptured";

    private final TransactionRunner transactions;
    private final PaymentIntentStore<Connection> intents;
    private final PaymentAttemptStore<Connection> attempts;
    private final ProviderEvidenceStore<Connection> evidence;
    private final PaymentProvider provider;
    private final PostingService postings;
    private final ChartOfAccounts<Connection> chart;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public PaymentCapture(
            TransactionRunner transactions,
            PaymentIntentStore<Connection> intents,
            PaymentAttemptStore<Connection> attempts,
            ProviderEvidenceStore<Connection> evidence,
            PaymentProvider provider,
            PostingService postings,
            ChartOfAccounts<Connection> chart,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.postings = Objects.requireNonNull(postings, "postings must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The states after this call — {@code CAPTURE_DISPATCHED} honestly included. */
    public record CaptureResult(
            PaymentIntentStatus intent, PaymentAttemptStatus attempt, boolean converged) {}

    /** Tx1's yield, carried across the connectionless gap. */
    private record Dispatch(
            PaymentIntentId intent,
            LedgerAccountId wallet,
            ProviderIdempotencyReference reference,
            ProviderReference authorization,
            Money amount) {}

    private record Tx1Outcome(Optional<Dispatch> dispatch, Optional<CaptureResult> converged) {}

    /**
     * Captures an authorized attempt, or converges on what already happened to it.
     *
     * @throws UnknownPaymentException the identifier names no attempt — a caller defect, the
     *     identifiers here come from the platform's own flows
     * @throws IllegalPaymentAttemptTransitionException the attempt has not been authorized —
     *     commanding its capture is a caller defect, loud
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public CaptureResult capture(PaymentAttemptId attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        // The whole command is the platform's act (PHASE_5_PLAN.md section 11): the
        // continuation of a confirmed intent has no session, whichever caller chains it.
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            Tx1Outcome tx1 =
                    transactions.inTransaction(uow -> dispatch(uow, attemptId, correlation));
            if (tx1.converged().isPresent()) {
                return tx1.converged().get();
            }
            Dispatch dispatch = tx1.dispatch().orElseThrow();

            // The provider call - between the transactions, holding no database connection
            // (ADR-0046, P1-TSK-026). An exception propagates: the dispatch stays committed
            // and visibly stranded, the sweeper's subject.
            ProviderAnswer answer =
                    provider.capture(
                            new PaymentProvider.CaptureRequest(
                                    dispatch.reference(),
                                    dispatch.authorization(),
                                    dispatch.amount()));

            return transactions.inTransaction(
                    uow -> applyCaptureOutcome(uow, dispatch, attemptId, answer, correlation));
        }
    }

    /** Tx1: converge or commit the capture dispatch — one row count decides. */
    private Tx1Outcome dispatch(
            Connection uow, PaymentAttemptId attemptId, Correlation correlation) {
        PaymentAttempt attempt =
                attempts.findById(uow, attemptId).orElseThrow(UnknownPaymentException::new);
        if (attempt.status() != PaymentAttemptStatus.AUTHORIZED) {
            return switch (attempt.status()) {
                // Already dispatched, unknown or terminal: converge on the truth - the
                // stranded cases are the sweeper's, never a second wire operation.
                case CAPTURE_DISPATCHED, CAPTURE_UNKNOWN, CAPTURED, FAILED ->
                        new Tx1Outcome(Optional.empty(), Optional.of(converged(uow, attempt)));
                // Commanding a capture on an unauthorized attempt is a caller defect, loud.
                default ->
                        throw new IllegalPaymentAttemptTransitionException(
                                attemptId,
                                attempt.status(),
                                PaymentAttemptStatus.CAPTURE_DISPATCHED);
            };
        }

        PaymentIntent intent =
                intents.findById(uow, attempt.intentId())
                        .orElseThrow(UnknownPaymentException::new);
        ProviderIdempotencyReference reference =
                new ProviderIdempotencyReference("cap-" + ids.next());
        if (!attempts.dispatchCapture(uow, attemptId, reference)) {
            // A racing dispatcher won between the read and the write: converge on its commit.
            PaymentAttempt current =
                    attempts.findById(uow, attemptId).orElseThrow(UnknownPaymentException::new);
            return new Tx1Outcome(Optional.empty(), Optional.of(converged(uow, current)));
        }

        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);
        attempts.recordTransition(
                uow,
                attemptId,
                PaymentAttemptStatus.AUTHORIZED,
                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                platform,
                now);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        PaymentsAuditAction.PAYMENT_CAPTURE_DISPATCHED,
                        PaymentCreation.TARGET_TYPE,
                        attempt.intentId().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only - never an amount (INV-AUD-02).
                        Optional.of(
                                "intent=" + attempt.intentId()
                                        + ", attempt=" + attemptId
                                        + ", reference=" + reference.value())));
        return new Tx1Outcome(
                Optional.of(
                        new Dispatch(
                                attempt.intentId(),
                                intent.walletAccount(),
                                reference,
                                attempt.authorizationProviderReference(),
                                attempt.authorizedAmount())),
                Optional.empty());
    }

    /** The converged answer: the attempt's truth and its intent's, no wire call. */
    private CaptureResult converged(Connection uow, PaymentAttempt attempt) {
        PaymentIntentStatus intentStatus =
                intents.findById(uow, attempt.intentId())
                        .orElseThrow(UnknownPaymentException::new)
                        .status();
        return new CaptureResult(intentStatus, attempt.status(), true);
    }

    /** Tx2: the outcome — and for APPROVED, the transition, THE POSTING and the intent, one commit. */
    private CaptureResult applyCaptureOutcome(
            Connection uow,
            Dispatch dispatch,
            PaymentAttemptId attemptId,
            ProviderAnswer answer,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        // Whatever the mapping said, what arrived is retained (INV-HIST-02).
        answer.evidence()
                .ifPresent(
                        bytes ->
                                evidence.append(
                                        uow,
                                        Optional.of(attemptId),
                                        Optional.empty(),
                                        EvidenceKind.RESPONSE,
                                        bytes,
                                        now));

        PaymentAttemptStatus committedAttempt;
        PaymentIntentStatus committedIntent = PaymentIntentStatus.PROCESSING;
        switch (answer.verdict()) {
            case APPROVED -> {
                if (attempts.capture(
                        uow,
                        attemptId,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        answer.providerReference().orElseThrow(),
                        dispatch.amount())) {
                    attempts.recordTransition(
                            uow,
                            attemptId,
                            PaymentAttemptStatus.CAPTURE_DISPATCHED,
                            PaymentAttemptStatus.CAPTURED,
                            platform,
                            now);

                    // THE POSTING - same connection, atomically with the transition
                    // (ADR-0048). DR clearing / CR wallet; the key makes any duplicate
                    // outcome structurally unable to post twice; no savepoint, deliberately
                    // (class javadoc): a posting failure fails this whole transaction loudly.
                    LedgerAccount clearing =
                            chart.resolve(
                                    uow,
                                    AccountPurpose.SETTLEMENT_CLEARING,
                                    dispatch.amount().currency());
                    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                    postings.post(
                            uow,
                            new PostingCommand(
                                    "payment-capture:" + attemptId.value(),
                                    today,
                                    today,
                                    attemptId.value().toString(),
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.DEBIT,
                                                    dispatch.amount()),
                                            new JournalLine(
                                                    dispatch.wallet(),
                                                    Direction.CREDIT,
                                                    dispatch.amount()))));

                    if (intents.transition(
                            uow,
                            dispatch.intent(),
                            PaymentIntentStatus.PROCESSING,
                            PaymentIntentStatus.SUCCEEDED)) {
                        intents.recordTransition(
                                uow,
                                dispatch.intent(),
                                PaymentIntentStatus.PROCESSING,
                                PaymentIntentStatus.SUCCEEDED,
                                platform,
                                now);
                    }
                    announce(uow, CAPTURED_EVENT_TYPE, dispatch.intent(), "CAPTURED",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.CAPTURED;
                committedIntent = PaymentIntentStatus.SUCCEEDED;
            }
            case DECLINED -> {
                committedAttempt =
                        failBoth(uow, dispatch.intent(), attemptId,
                                PaymentFailureReason.DECLINED, correlation, platform, now);
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                committedAttempt =
                        failBoth(uow, dispatch.intent(), attemptId,
                                PaymentFailureReason.PROVIDER_UNAVAILABLE, correlation,
                                platform, now);
                committedIntent = PaymentIntentStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: CAPTURE_UNKNOWN commits with NOTHING POSTED (INV-LIFE-03) -
                // the resolution query answers "did the capture happen?", and the posting
                // arrives only with a resolved CAPTURED.
                if (attempts.markCaptureUnknown(uow, attemptId)) {
                    attempts.recordTransition(
                            uow,
                            attemptId,
                            PaymentAttemptStatus.CAPTURE_DISPATCHED,
                            PaymentAttemptStatus.CAPTURE_UNKNOWN,
                            platform,
                            now);
                    announce(uow, PaymentConfirmation.UNKNOWN_EVENT_TYPE, dispatch.intent(),
                            "CAPTURE_UNKNOWN", Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.CAPTURE_UNKNOWN;
            }
        }

        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        PaymentsAuditAction.PAYMENT_OUTCOME_APPLIED,
                        PaymentCreation.TARGET_TYPE,
                        dispatch.intent().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "attempt=" + attemptId
                                        + ", verdict=" + answer.verdict()
                                        + ", attemptStatus=" + committedAttempt
                                        + ", intentStatus=" + committedIntent)));
        return new CaptureResult(committedIntent, committedAttempt, false);
    }

    /** The attempt fails with its mapped reason and the intent fails with it. */
    private PaymentAttemptStatus failBoth(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentFailureReason reason,
            Correlation correlation,
            Actor platform,
            Instant now) {
        if (attempts.fail(uow, attemptId, PaymentAttemptStatus.CAPTURE_DISPATCHED, reason)) {
            attempts.recordTransition(
                    uow,
                    attemptId,
                    PaymentAttemptStatus.CAPTURE_DISPATCHED,
                    PaymentAttemptStatus.FAILED,
                    platform,
                    now);
            if (intents.transition(
                    uow, intentId, PaymentIntentStatus.PROCESSING,
                    PaymentIntentStatus.FAILED)) {
                intents.recordTransition(
                        uow,
                        intentId,
                        PaymentIntentStatus.PROCESSING,
                        PaymentIntentStatus.FAILED,
                        platform,
                        now);
            }
            announce(uow, PaymentConfirmation.FAILED_EVENT_TYPE, intentId, "FAILED",
                    Optional.of(reason), correlation, now);
        }
        return PaymentAttemptStatus.FAILED;
    }

    /** The outcome's event, in the transaction that commits the fact ({@code INV-EVT-01}). */
    private void announce(
            Connection uow,
            String eventType,
            PaymentIntentId intentId,
            String status,
            Optional<PaymentFailureReason> reason,
            Correlation correlation,
            Instant now) {
        EventPayload payload = EventPayload.of().with("status", status);
        if (reason.isPresent()) {
            payload = payload.with("failureReason", reason.get().name());
        }
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        PaymentCreation.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        intentId,
                        PaymentCreation.TARGET_TYPE,
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
