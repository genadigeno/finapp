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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public final class PaymentCapture {

    static final String CAPTURED_EVENT_TYPE = "payments.PaymentCaptured";

    @NonNull private final TransactionRunner transactions;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final ProviderEvidenceStore<Connection> evidence;
    @NonNull private final PaymentProvider provider;
    @NonNull private final PaymentOutcomes outcomes;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The states after this call — {@code CAPTURE_DISPATCHED} honestly included.
     *
     * @param converged this call lost Tx1's conditional dispatch and never asked the provider
     * @param acting this call's own outcome transition made the committed state
     *     (`P5-TSK-017`): the ten-way race's nine losers report false, so the chained
     *     capture is counted once however many chainers ran
     */
    public record CaptureResult(
            PaymentIntentStatus intent,
            PaymentAttemptStatus attempt,
            boolean converged,
            boolean acting) {}

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
        return new CaptureResult(intentStatus, attempt.status(), true, false);
    }

    /**
     * Tx2: the verbatim evidence, then the one shared outcome application
     * ({@link PaymentOutcomes} — `P5-TSK-013`'s extraction: for APPROVED the transition, THE
     * POSTING and the intent's {@code SUCCEEDED} stay one commit, whichever resolver calls).
     */
    private CaptureResult applyCaptureOutcome(
            Connection uow,
            Dispatch dispatch,
            PaymentAttemptId attemptId,
            ProviderAnswer answer,
            Correlation correlation) {
        PaymentOutcomes.Applied applied =
                outcomes.applyCapture(
                        uow,
                        dispatch.intent(),
                        attemptId,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        answer.verdict(),
                        answer.providerReference(),
                        dispatch.wallet(),
                        dispatch.amount(),
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
        return new CaptureResult(
                applied.intent(), applied.attempt(), false, applied.acting());
    }
}
