package com.finapp.payments;

import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The refund command: hold, then post (`P5-TSK-015`, ADR-0048 §4) — the same
 * dispatch-before-call choreography as every provider operation, with `P3-TSK-015`'s owed
 * composition held at its centre: <strong>the dispatch transaction reserves the customer's
 * funds with a Phase 3 hold placed inside the account-row lock</strong>, so the money a
 * provider may be about to return is unspendable for the whole flight of its indecision
 * ({@code INV-BAL-04} doing refund duty).
 *
 * <h2>The bound, at two ranks by design</h2>
 *
 * <p>The domain judges {@code sum(non-FAILED) + this ≤ captured} <strong>under the command's
 * {@code FOR UPDATE} on the attempt row</strong> — the lock-then-look contract
 * {@link Refund#create}'s javadoc has carried since `P5-TSK-007`, because two dispatchers
 * summing without the lock is the `P2-TSK-015` write-skew shape. `V004`'s {@code BEFORE
 * INSERT} trigger holds the identical bound under advisory-lock namespace 3 for every writer
 * that never ran this code ({@code INV-PAY-05}, {@code INV-REV-02}).
 *
 * <h2>Lock order: attempt → account, always</h2>
 *
 * <p>The attempt lock is taken before {@link HoldService#place}'s account lock — the same
 * attempt→account order the capture outcome uses — so the two money paths cannot deadlock
 * each other (the `P5-TSK-013` 40P01 lesson, applied in advance rather than found again).
 *
 * <h2>The three honest outcomes</h2>
 *
 * <p>Completion <strong>releases-and-posts atomically</strong> (DR wallet / CR clearing, key
 * {@code payment-refund:<refundId>} — the capture's exact inverse pair); failure releases
 * with nothing posted; ambiguity commits {@code UNKNOWN} <strong>with the hold
 * standing</strong> — {@code INV-LIFE-03} with money visibly parked on it. All through
 * {@link PaymentOutcomes#applyRefund}, the one code path `P5-TSK-016`'s webhook resolver
 * consumes next.
 *
 * <h2>The operator commands; the platform applies</h2>
 *
 * <p>The dispatch is the operator's reasoned act ({@code PAYMENT_REFUND} checked at the
 * boundary, the reason required — {@code INV-AUD-03}); the outcome is the platform's, through
 * this class's enumerated {@code enterSystem()} site — the `P5-TSK-009` reasoning, refund
 * form.
 */
public final class PaymentRefund {

    static final String IDEMPOTENCY_SCOPE = "payment.refund";

    private final TransactionRunner transactions;
    private final IdempotentExecutor executor;
    private final PaymentIntentStore<Connection> intents;
    private final PaymentAttemptStore<Connection> attempts;
    private final RefundStore<Connection> refunds;
    private final ProviderEvidenceStore<Connection> evidence;
    private final HoldService holds;
    private final PaymentProvider provider;
    private final PaymentOutcomes outcomes;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public PaymentRefund(
            TransactionRunner transactions,
            IdempotentExecutor executor,
            PaymentIntentStore<Connection> intents,
            PaymentAttemptStore<Connection> attempts,
            RefundStore<Connection> refunds,
            ProviderEvidenceStore<Connection> evidence,
            HoldService holds,
            PaymentProvider provider,
            PaymentOutcomes outcomes,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.refunds = Objects.requireNonNull(refunds, "refunds must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What the operator learns — the status honestly, {@code UNKNOWN} included. */
    public record RefundResult(RefundId refund, RefundStatus status, boolean replayed) {}

    /** Tx1's yield, carried across the connectionless gap. */
    private record Dispatch(Refund refund, PaymentIntentId intent, LedgerAccountId wallet,
            ProviderReference capture) {}

    /**
     * Dispatches (or replays) the refund and applies the provider's answer.
     *
     * @throws UnknownPaymentException the intent names nothing — the caller's one 404
     * @throws PaymentNotRefundableException no captured attempt — the caller's 409
     * @throws RefundExceedsCaptureException the bound — the caller's 422
     * @throws com.finapp.ledger.HoldExceedsAvailableBalanceException the wallet cannot fund
     *     the return now ({@code INV-BAL-04}) — the caller's 409, nothing written
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key was used
     *     for a materially different request ({@code INV-IDEM-03})
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public RefundResult refund(
            PaymentIntentId intentId, Money amount, String reason, String idempotencyKey) {
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Actor operator = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        // Tx1: the claim and the dispatch - the hold, the row and the audit, one commit,
        // before the provider can possibly have acted (ADR-0046).
        Tx1 tx1 =
                transactions.inTransaction(
                        uow -> {
                            IdempotentExecutor.ExecutionOutcome outcome =
                                    executor.execute(
                                            uow,
                                            new IdempotencyKey(IDEMPOTENCY_SCOPE, idempotencyKey),
                                            RequestFingerprint.sha256(
                                                    canonicalForm(
                                                            operator, intentId, amount, reason)),
                                            claimed ->
                                                    dispatch(
                                                            claimed, intentId, amount, reason,
                                                            operator, correlation));
                            RefundId refundId =
                                    RefundId.of(
                                            UUID.fromString(
                                                    new String(
                                                            outcome.body().orElseThrow(),
                                                            StandardCharsets.UTF_8)));
                            if (outcome.replayed()) {
                                // The retry of a lost response: no second hold, no second
                                // wire call - the truth as it stands now, honestly.
                                Refund current =
                                        refunds.findById(uow, refundId).orElseThrow();
                                return new Tx1(
                                        Optional.of(
                                                new RefundResult(
                                                        refundId, current.status(), true)),
                                        null);
                            }
                            Refund fresh = refunds.findById(uow, refundId).orElseThrow();
                            PaymentIntent intent =
                                    intents.findById(uow, intentId).orElseThrow();
                            PaymentAttempt attempt =
                                    attempts.findForIntent(uow, intentId).orElseThrow();
                            return new Tx1(
                                    Optional.empty(),
                                    new Dispatch(
                                            fresh,
                                            intentId,
                                            intent.walletAccount(),
                                            attempt.captureProviderReference()));
                        });
        if (tx1.replayed().isPresent()) {
            return tx1.replayed().get();
        }
        Dispatch dispatch = tx1.dispatch();

        // The provider call - between the transactions, holding no database connection
        // (ADR-0046, P1-TSK-026). An exception propagates: the dispatch stays committed with
        // its hold standing and visible - never a fabricated outcome.
        ProviderAnswer answer =
                provider.refund(
                        new PaymentProvider.RefundRequest(
                                dispatch.refund().providerIdempotencyReference(),
                                dispatch.capture(),
                                dispatch.refund().amount()));

        // Tx2: the outcome, applied as the platform - a provider's answer has no session
        // (the enumerated enterSystem() site, refund form).
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        RefundStatus committed =
                                outcomes.applyRefund(
                                        uow,
                                        dispatch.intent(),
                                        dispatch.refund(),
                                        RefundStatus.DISPATCHED,
                                        answer.verdict(),
                                        answer.providerReference(),
                                        dispatch.wallet(),
                                        correlation);
                        // Whatever the mapping said, what arrived is retained (INV-HIST-02) -
                        // AFTER the outcome's row lock (the P5-TSK-013 lock-order rule).
                        answer.evidence()
                                .ifPresent(
                                        bytes ->
                                                evidence.append(
                                                        uow,
                                                        Optional.empty(),
                                                        Optional.of(dispatch.refund().id()),
                                                        EvidenceKind.RESPONSE,
                                                        bytes,
                                                        Instant.now(clock)));
                        return new RefundResult(dispatch.refund().id(), committed, false);
                    });
        }
    }

    private record Tx1(Optional<RefundResult> replayed, Dispatch dispatch) {}

    /** The claimed dispatch: bound under the attempt lock, hold inside the account lock. */
    private CommandResult dispatch(
            Connection uow,
            PaymentIntentId intentId,
            Money amount,
            String reason,
            Actor operator,
            Correlation correlation) {
        PaymentIntent intent =
                intents.findById(uow, intentId).orElseThrow(UnknownPaymentException::new);
        PaymentAttempt loose =
                attempts.findForIntent(uow, intentId)
                        .orElseThrow(PaymentNotRefundableException::noAttempt);

        // THE LOCK, then the look (P5-TSK-015): the sibling sum is current because no other
        // dispatcher can pass this point until we commit or roll back.
        PaymentAttempt attempt =
                attempts.lockById(uow, loose.id()).orElseThrow(UnknownPaymentException::new);
        if (attempt.status() != PaymentAttemptStatus.CAPTURED) {
            throw new PaymentNotRefundableException(attempt.status());
        }
        Money alreadyRefunded =
                refunds.sumNonFailedFor(uow, attempt.id(), attempt.capturedAmount().currency());
        if (!amount.currency().equals(attempt.capturedAmount().currency())
                || amount.plus(alreadyRefunded).compareTo(attempt.capturedAmount()) > 0) {
            // The honest 422 BEFORE any hold is placed: nothing written, nothing reserved.
            throw new RefundExceedsCaptureException(attempt.capturedAmount().currency());
        }

        // The Phase 3 hold, placed inside the account-row lock (ADR-0048 §4): from this
        // commit until the outcome, the money a provider may return is unspendable
        // (INV-BAL-04). Throws with nothing written when the wallet cannot fund it.
        com.finapp.ledger.Hold hold = holds.place(uow, intent.walletAccount(), amount);

        Refund refund =
                Refund.create(
                        ids,
                        clock,
                        attempt,
                        amount,
                        alreadyRefunded,
                        reason,
                        hold.id(),
                        new ProviderIdempotencyReference("rfd-" + ids.next()));
        refunds.insert(uow, refund);

        Instant now = Instant.now(clock);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        operator,
                        now,
                        PaymentsAuditAction.PAYMENT_REFUND_DISPATCHED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        // The REQUIRED reason (INV-AUD-03): the operator's own words, in the
                        // record's reason field where the reversal precedent put it.
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only - never an amount (INV-AUD-02).
                        Optional.of(
                                "intent=" + intentId
                                        + ", attempt=" + attempt.id()
                                        + ", refund=" + refund.id()
                                        + ", reference="
                                        + refund.providerIdempotencyReference().value())));

        return CommandResult.succeeded(
                StoredResponse.of(
                        refund.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    /** The operator and the money's meaning ({@code INV-IDEM-03}); correlation excluded. */
    private static byte[] canonicalForm(
            Actor operator, PaymentIntentId intentId, Money amount, String reason) {
        return (IDEMPOTENCY_SCOPE
                        + "|" + operator.id()
                        + "|" + intentId.value()
                        + "|" + amount.minorUnits()
                        + "|" + amount.currency().code()
                        + "|" + amount.scale()
                        + "|" + reason)
                .getBytes(StandardCharsets.UTF_8);
    }
}
