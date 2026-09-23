package com.finapp.app.payments;

import com.finapp.app.telemetry.PaymentMeters;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.payments.IllegalPaymentIntentTransitionException;
import com.finapp.payments.NoWalletForPaymentException;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentCancellation;
import com.finapp.payments.PaymentCapture;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentCurrencyMismatchException;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentNotRefundableException;
import com.finapp.payments.PaymentRefund;
import com.finapp.payments.RefundExceedsCaptureException;
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.payments.UnknownPaymentException;
import com.finapp.payments.UnknownPaymentInstrumentException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/payments` slice behind {@link PaymentController} (`P5-TSK-011`).
 *
 * <h2>One idempotency claim, the command's</h2>
 *
 * <p>{@link PaymentCreation} already claims at the financial boundary — scope
 * {@code payment.create}, the caller's key, the fingerprint binding the actor and the money's
 * meaning ({@code INV-IDEM-01/-03}) — so this surface adds <strong>no second layer</strong>
 * (the {@code TransferService} recorded rule). What makes a retried {@code POST} byte-for-byte
 * identical: the <em>status</em> is rendered from the replayed judgement
 * ({@code CreationResult} — the original {@code REQUIRES_CONFIRMATION}, never a re-read, so a
 * confirmation cannot leak into a replay), and every other field was written at birth and never
 * again — the intent's status-independent coherence, asserted per field at the aggregate and
 * held by `V002`'s one-column {@code UPDATE} grant.
 *
 * <h2>The surface is the capture's chainer</h2>
 *
 * <p>When the post-confirm state is {@code AUTHORIZED} — converged or not —
 * {@link PaymentCapture} is chained (its own recorded contract: <em>"chained by the surface
 * after a synchronous {@code AUTHORIZED}"</em>). Chaining on the <em>converged</em>
 * {@code AUTHORIZED} too is what makes a client retry the recovery for an {@code AUTHORIZED}
 * stranded by a crash between the confirm's outcome and the chain: the retry converges on the
 * confirm with zero wire calls, then finishes the capture. The chain adds no race an instance
 * count could widen: {@code dispatchCapture}'s conditional row count admits one dispatcher and
 * the posting's {@code payment-capture:} claim is the third wall (`P5-TSK-010`'s counted
 * ten-way). {@code AUTH_UNKNOWN} chains nothing (the sweeper's, `P5-TSK-014`);
 * {@code CAPTURE_UNKNOWN} leaves the intent {@code PROCESSING} and the answer says so — the
 * asynchronous-outcome contract shape, honestly ({@code INV-LIFE-03} at the contract).
 *
 * <h2>Transaction boundaries are the commands' own</h2>
 *
 * <p>Create and cancel run in one transaction here. Confirm and capture are <strong>not
 * wrapped</strong>: each runs its own dispatch-before-call choreography through
 * {@code TransactionRunner}, and a surrounding transaction would re-create the
 * connection-held-across-a-provider-call defect ADR-0046 exists to kill.
 */
public final class PaymentService {

    private final PaymentCreation creation;
    private final PaymentCancellation cancellation;
    private final ObjectProvider<PaymentConfirmation> confirmation;
    private final ObjectProvider<PaymentCapture> capture;
    private final ObjectProvider<PaymentRefund> refund;
    private final PaymentIntentStore<Connection> intents;
    private final PaymentAttemptStore<Connection> attempts;
    private final com.finapp.payments.RefundStore<Connection> refunds;
    private final com.finapp.app.telemetry.PaymentMeters meters;
    private final IdentityStore<Connection> identities;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public PaymentService(
            PaymentCreation creation,
            PaymentCancellation cancellation,
            ObjectProvider<PaymentConfirmation> confirmation,
            ObjectProvider<PaymentCapture> capture,
            ObjectProvider<PaymentRefund> refund,
            PaymentIntentStore<Connection> intents,
            PaymentAttemptStore<Connection> attempts,
            com.finapp.payments.RefundStore<Connection> refunds,
            com.finapp.app.telemetry.PaymentMeters meters,
            IdentityStore<Connection> identities,
            TransactionTemplate paymentTransactions,
            DataSource dataSource) {
        this.creation = Objects.requireNonNull(creation, "creation must not be null");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation must not be null");
        this.capture = Objects.requireNonNull(capture, "capture must not be null");
        this.refund = Objects.requireNonNull(refund, "refund must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.refunds = Objects.requireNonNull(refunds, "refunds must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.transactions =
                Objects.requireNonNull(paymentTransactions, "paymentTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * The rendered intent — the asynchronous-outcome contract shape: {@code status} always and
     * honestly ({@code PROCESSING} included), {@code failureReason} the <strong>mapped</strong>
     * enum exactly when {@code FAILED} ({@code INV-PAY-03}: the provider's own code lives in the
     * retained evidence, never here). {@code paymentMethodId} is echoed because the caller
     * supplied it and needs to tell payments apart in a list — their own instrument, nobody
     * else's. <strong>Deliberately no ledger and no attempt identifiers</strong>: the payment
     * tables store no entry id (the entry's reference IS the attempt id — one fact, one place),
     * and internal accounting vocabulary stays out of the customer's view (the `P3-TSK-018`
     * reasoning); the §12 chain stays walkable by <em>stored</em> identifier, and the refund
     * view (`P5-TSK-016`) owns any revisit. The amount is a decimal string (`P3-TSK-013`),
     * disclosed only to the customer it belongs to.
     */
    public record PaymentView(
            String id,
            String status,
            String failureReason,
            String amount,
            String currency,
            String paymentMethodId,
            String createdAt,
            String refunded,
            String refundPending) {}

    /**
     * Creates (or replays) the caller's payment intent — {@code 201} for the replay as well as
     * the creation (the convergence idiom: what distinguishes the creating call is the records,
     * never the answer).
     */
    public PaymentView create(Session current, PaymentCreateRequest body, String idempotencyKey) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        Money amount = parsedAmount(body.amount(), body.currency());
        // Malformed folds into the instrument refusal (malformed-equals-absent): the resolution
        // port answers unknown and not-yours with one empty, and the fold keeps malformed
        // indistinguishable from both.
        UUID methodId = parsedOr(body.paymentMethodId(), PaymentService::unknownInstrument);

        return inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    PaymentCreation.CreationResult result;
                    try {
                        result =
                                creation.create(
                                        unitOfWork,
                                        new PaymentCreation.CreatePaymentCommand(
                                                partyId, methodId, amount, idempotencyKey));
                    } catch (NoWalletForPaymentException refused) {
                        throw new ApiException(
                                PaymentsErrorCode.NO_WALLET,
                                "A payment was refused: the caller has no wallet");
                    } catch (UnknownPaymentInstrumentException refused) {
                        throw unknownInstrument();
                    } catch (PaymentCurrencyMismatchException refused) {
                        throw new ApiException(
                                PaymentsErrorCode.CURRENCY_MISMATCH,
                                "A payment was refused: the currency is not the wallet's");
                    }
                    PaymentIntent row =
                            intents.findById(unitOfWork, result.intent())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "a judged creation has a row: the"
                                                                    + " command inserted or"
                                                                    + " replayed it"));
                    // Status from the RESULT, not the row: a replay must render the original
                    // judgement (REQUIRES_CONFIRMATION) byte for byte, whatever confirmation
                    // has done to the row since. No reason ever: nothing has been judged.
                    // Totals fixed at the judgement's zeros, not a re-read: at creation nothing is
                    // dispatched, and the replay must render the original bytes whatever
                    // refunds have done to the rows since (the P5-TSK-011 doctrine).
                    return view(row, result.status(), null, zeroTotals(row));
                });
    }

    /**
     * Confirms the caller's intent, chains the capture after a synchronous {@code AUTHORIZED},
     * and answers the intent's real state — honestly {@code PROCESSING} when that is the truth.
     */
    public PaymentView confirm(Session current, PaymentIntentId intentId) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");

        // Absent provider = unavailable: an unconfigured deployment keeps a stable contract
        // and answers the honest 503 (the ObjectProvider decision, recorded in PaymentBeans).
        PaymentConfirmation confirm = confirmation.getIfAvailable();
        if (confirm == null) {
            throw providerUnavailable();
        }

        UUID partyId = inOneTransaction(unitOfWork -> partyOf(unitOfWork, current));

        PaymentConfirmation.ConfirmationResult confirmed;
        try {
            // NOT wrapped in a transaction: the command runs its own Tx1 / provider call /
            // Tx2 choreography and must hold no connection during the call (ADR-0046).
            confirmed = confirm.confirm(partyId, intentId);
        } catch (UnknownPaymentException unknown) {
            throw paymentNotFound();
        } catch (UnknownPaymentInstrumentException detached) {
            // Detached since creation: nothing written, the intent still awaits confirmation —
            // folded with unknown/not-yours/malformed at create (one refusal, one shape).
            throw unknownInstrument();
        } catch (IllegalPaymentIntentTransitionException refused) {
            throw new ApiException(
                    PaymentsErrorCode.NOT_CONFIRMABLE,
                    "A confirmation was refused by the intent's state (" + refused.from() + ")",
                    "the payment is " + refused.from()
                            + " and only a payment awaiting confirmation can be confirmed.");
        }

        // The judgement this call itself committed, counted AFTER the command's own
        // transaction returned (`P5-TSK-017`): a converged answer and a Tx2 that lost to a
        // resolver both report acting=false, so one judgement is counted once however many
        // callers raced for it. An outcome that rolled back never reaches here.
        countAttempt(confirmed.acting(), confirmed.attempt().orElse(null));

        // The chain (PaymentCapture's own contract: "chained by the surface after a
        // synchronous AUTHORIZED") - on the converged answer too, which is what makes a
        // client retry the recovery path for an AUTHORIZED stranded by a crash here.
        if (confirmed.attempt().filter(status -> status == PaymentAttemptStatus.AUTHORIZED)
                .isPresent()) {
            PaymentCapture capturing = capture.getIfAvailable();
            if (capturing == null) {
                throw new IllegalStateException(
                        "PaymentConfirmation exists without PaymentCapture: the beans share"
                                + " one condition");
            }
            PaymentAttempt attempt =
                    inOneTransaction(
                            unitOfWork ->
                                    attempts.findForIntent(unitOfWork, intentId)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "an AUTHORIZED answer means"
                                                                            + " an attempt row"
                                                                            + " exists")));
            // A concurrent chainer is harmless: the capture converges (P5-TSK-010's counted
            // ten-way race - one wire operation, one journal entry, the rest converged).
            PaymentCapture.CaptureResult captured = capturing.capture(attempt.id());
            countAttempt(captured.acting(), captured.attempt());
        }

        // The answer is the CURRENT state - what confirm promises is the truth, not an echo:
        // SUCCEEDED after the chain, FAILED with its mapped reason, or honestly PROCESSING.
        return inOneTransaction(
                unitOfWork ->
                        currentView(unitOfWork, intentId, partyId)
                                .orElseThrow(PaymentService::paymentNotFound));
    }

    /** Cancels the caller's intent — winning only the confirmation window (ADR-0045). */
    public PaymentView cancel(Session current, PaymentIntentId intentId) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        return inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    try {
                        cancellation.cancel(unitOfWork, partyId, intentId);
                    } catch (UnknownPaymentException unknown) {
                        throw paymentNotFound();
                    } catch (IllegalPaymentIntentTransitionException refused) {
                        throw new ApiException(
                                PaymentsErrorCode.NOT_CANCELLABLE,
                                "A cancellation was refused by the intent's state ("
                                        + refused.from() + ")",
                                "the payment is " + refused.from()
                                        + " and can no longer be cancelled.");
                    }
                    return currentView(unitOfWork, intentId, partyId)
                            .orElseThrow(PaymentService::paymentNotFound);
                });
    }

    /**
     * The caller's payment — or empty, one answer for not-yours, does-not-exist and malformed
     * alike (the controller's one 404).
     */
    public Optional<PaymentView> find(Session current, PaymentIntentId intentId) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        return inOneTransaction(
                unitOfWork ->
                        currentView(unitOfWork, intentId, partyOf(unitOfWork, current)));
    }

    /**
     * The operator's rendered refund — the status honestly, {@code UNKNOWN} included. Lean by
     * scope: the customer-facing refund view with derived totals is `P5-TSK-016`'s.
     */
    public record RefundView(String id, String status, String amount, String currency) {}

    /**
     * Dispatches (or replays) the operator's refund (`P5-TSK-015`) — the operator surface
     * shape: the URL names somebody else's payment, the standing checks are
     * {@code @RequiresPermission(PAYMENT_REFUND)} at the boundary plus the actor the
     * {@code SecurityContext} carries into the command and its audit record.
     */
    public RefundView refundPayment(
            PaymentIntentId intentId, RefundRequest body, String idempotencyKey) {
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(body, "body must not be null");
        PaymentRefund command = refund.getIfAvailable();
        if (command == null) {
            throw providerUnavailable();
        }
        Money amount = parsedAmount(body.amount(), body.currency());
        PaymentRefund.RefundResult result;
        try {
            // NOT wrapped in a transaction: the command runs its own Tx1 / provider call /
            // Tx2 choreography (ADR-0046) - the confirm's discipline, refund form.
            result = command.refund(intentId, amount, body.reason(), idempotencyKey);
        } catch (UnknownPaymentException unknown) {
            throw paymentNotFound();
        } catch (PaymentNotRefundableException refused) {
            throw new ApiException(
                    PaymentsErrorCode.NOT_REFUNDABLE,
                    "A refund was refused by the attempt's state",
                    refused.status() == null
                            ? "the payment was never dispatched and only a captured payment"
                                    + " can be refunded."
                            : "the payment is " + refused.status()
                                    + " and only a captured payment can be refunded.");
        } catch (RefundExceedsCaptureException refused) {
            throw new ApiException(
                    PaymentsErrorCode.REFUND_EXCEEDS_CAPTURED,
                    "A refund was refused by the capture bound (INV-PAY-05)");
        } catch (com.finapp.ledger.HoldExceedsAvailableBalanceException unfunded) {
            throw new ApiException(
                    PaymentsErrorCode.REFUND_UNFUNDED,
                    "A refund could not reserve what it takes from the account it debits"
                            + " (INV-BAL-04, ADR-0054)");
        }
        countRefund(result);
        return new RefundView(
                result.refund().value().toString(),
                result.status().name(),
                amount.toBigDecimal().toPlainString(),
                amount.currency().code());
    }

    /**
     * The acting attempt judgement, in the machine's own vocabulary (`P5-TSK-017`). A
     * dispatched-but-unanswered state is no judgement at all and counts nothing: what the
     * plan's counter measures is what was DECIDED, and {@code *_DISPATCHED} is the platform
     * mid-question. {@code *_UNKNOWN} is a decision — the honest one ({@code INV-LIFE-03}).
     */
    private void countAttempt(boolean acting, PaymentAttemptStatus status) {
        if (!acting || status == null) {
            return;
        }
        switch (status) {
            case AUTHORIZED -> meters.attempt(PaymentMeters.Judgement.AUTHORIZED);
            case CAPTURED -> meters.attempt(PaymentMeters.Judgement.CAPTURED);
            case FAILED -> meters.attempt(PaymentMeters.Judgement.FAILED);
            case AUTH_UNKNOWN, CAPTURE_UNKNOWN ->
                    meters.attempt(PaymentMeters.Judgement.UNKNOWN);
            case AUTH_DISPATCHED, CAPTURE_DISPATCHED -> {
                // Mid-question: nothing has been judged yet.
            }
        }
    }

    /** The acting refund judgement — a replay and a converged takeover count nothing. */
    private void countRefund(PaymentRefund.RefundResult result) {
        if (!result.acting()) {
            return;
        }
        switch (result.status()) {
            case COMPLETED -> meters.refund(PaymentMeters.RefundOutcome.COMPLETED);
            case FAILED -> meters.refund(PaymentMeters.RefundOutcome.FAILED);
            case UNKNOWN -> meters.refund(PaymentMeters.RefundOutcome.UNKNOWN);
            case DISPATCHED -> {
                // Mid-question, as above.
            }
        }
    }

    /** The caller's payments, newest first. */
    public List<PaymentView> list(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    return intents.listFor(unitOfWork, partyId).stream()
                            .map(row -> view(row, row.status(), reasonFor(unitOfWork, row),
                                    liveTotals(unitOfWork, row)))
                            .toList();
                });
    }

    // -----------------------------------------------------------------

    /** The row's current state, reason included — the GET/confirm/cancel rendering. */
    private Optional<PaymentView> currentView(
            Connection unitOfWork, PaymentIntentId intentId, UUID partyId) {
        return intents.findOwned(unitOfWork, intentId, partyId)
                .map(row ->
                        view(row, row.status(), reasonFor(unitOfWork, row),
                                liveTotals(unitOfWork, row)));
    }

    /**
     * The mapped reason, exactly when the intent is {@code FAILED} — the attempt's fact
     * ({@code PAYMENT_LIFECYCLES.md} §2: the intent deliberately carries no reason vocabulary),
     * read only when it can exist.
     */
    private String reasonFor(Connection unitOfWork, PaymentIntent row) {
        if (row.status() != PaymentIntentStatus.FAILED) {
            return null;
        }
        return attempts.findForIntent(unitOfWork, row.id())
                .map(PaymentAttempt::failureReason)
                .map(Enum::name)
                .orElse(null);
    }

    private PaymentView view(
            PaymentIntent row, PaymentIntentStatus status, String reason, RefundTotals totals) {
        return new PaymentView(
                row.id().value().toString(),
                status.name(),
                reason,
                row.amount().toBigDecimal().toPlainString(),
                row.amount().currency().code(),
                row.paymentMethodId().toString(),
                row.createdAt().toString(),
                totals.refunded(),
                totals.pending());
    }

    /**
     * The refund totals, DERIVED from the refund rows at read time (`P5-TSK-016`, ADR-0045:
     * the intent carries no refund state, so there is nothing to drift) — {@code refunded}
     * is the {@code COMPLETED} sum, {@code refundPending} the {@code DISPATCHED}+{@code
     * UNKNOWN} sum: the customer's money parked behind a standing hold, made visible.
     * {@code FAILED} refunds count in neither, which is the freed-budget arithmetic.
     */
    private record RefundTotals(String refunded, String pending) {}

    private RefundTotals zeroTotals(PaymentIntent row) {
        return new RefundTotals(
                zero(row.amount()).toBigDecimal().toPlainString(),
                zero(row.amount()).toBigDecimal().toPlainString());
    }

    private static Money zero(Money like) {
        return Money.ofMinorUnits(0, like.currency());
    }

    private RefundTotals liveTotals(Connection unitOfWork, PaymentIntent row) {
        return attempts.findForIntent(unitOfWork, row.id())
                .map(
                        attempt -> {
                            long refunded = 0;
                            long pending = 0;
                            for (com.finapp.payments.Refund refund :
                                    refunds.listFor(unitOfWork, attempt.id())) {
                                switch (refund.status()) {
                                    case COMPLETED -> refunded += refund.amount().minorUnits();
                                    case DISPATCHED, UNKNOWN ->
                                            pending += refund.amount().minorUnits();
                                    case FAILED -> {
                                        // Freed budget: counts in neither total.
                                    }
                                }
                            }
                            return new RefundTotals(
                                    Money.ofMinorUnits(refunded, row.amount().currency())
                                            .toBigDecimal()
                                            .toPlainString(),
                                    Money.ofMinorUnits(pending, row.amount().currency())
                                            .toBigDecimal()
                                            .toPlainString());
                        })
                .orElseGet(() -> zeroTotals(row));
    }

    /**
     * Exact, or the caller's 422 naming the field ({@code INV-MON-03} at the inbound boundary
     * — the {@code TransferService} idiom): never a rounding, and a non-positive amount is
     * refused here so the aggregate's defence-in-depth refusal is never our 500.
     */
    private static Money parsedAmount(String raw, String currencyRaw) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(currencyRaw);
        } catch (IllegalArgumentException unusable) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payment named a currency the platform cannot express amounts in",
                    "'currency' must be an ISO 4217 currency with a minor unit.");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw);
        } catch (NumberFormatException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payment amount was not a decimal number",
                    "'amount' must be a decimal string such as \"12.50\".");
        }
        Money amount;
        try {
            amount = Money.of(decimal, currency);
        } catch (MonetaryException inexact) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payment amount was not representable at the currency's scale",
                    "'amount' is not representable at the currency's scale.");
        }
        if (!amount.isPositive()) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payment amount was not strictly positive",
                    "'amount' must be strictly positive.");
        }
        return amount;
    }

    private static UUID parsedOr(String raw, java.util.function.Supplier<ApiException> refusal) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw refusal.get();
        }
    }

    private static ApiException unknownInstrument() {
        return new ApiException(
                PaymentsErrorCode.UNKNOWN_INSTRUMENT,
                "A payment instrument resolved to no active method of the caller's");
    }

    private static ApiException providerUnavailable() {
        return new ApiException(
                PaymentsErrorCode.PROVIDER_UNAVAILABLE,
                "A confirmation arrived on a deployment with no payment provider configured");
    }

    private static ApiException paymentNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No payment of the caller's matches the requested identifier",
                "no such payment");
    }

    /** {@code Session → Identity → Party}: the {@code TransferService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A proven session resolved to no identity; registration"
                                                + " should make this impossible"));
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
