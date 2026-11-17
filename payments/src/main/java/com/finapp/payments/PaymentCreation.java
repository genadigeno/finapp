package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The intent-creation command ({@code P5-TSK-009}, ADR-0045): acceptance, never execution —
 * one transaction commits the intent at {@code REQUIRES_CONFIRMATION}, its audit record and
 * {@code payments.PaymentIntentCreated}, and <strong>nothing is dispatched and nothing
 * posts</strong> (ADR-0048). Confirmation is a separate act, which is the window that gives
 * {@code CANCELLED} its producer.
 *
 * <h2>The claim is at the financial boundary</h2>
 *
 * <p>Scope {@code payment.create:<keyedType>:<actorId>} — the command and its principal, so one
 * client's key value never refuses another's (`X-TSK-003`, ADR-0004) — the caller's key, and a
 * fingerprint binding <strong>the actor</strong> and the money's meaning — party, wallet
 * account, instrument, amount, currency, scale (the backlog's list; {@code INV-IDEM-03}'s
 * subjects, the actor per ADR-0004). The stored body replays {@code intentId|status}. For the
 * release that moves it, the claim under the bare {@code payment.create} is consulted first, so
 * a retry spanning the move replays rather than creating a second intent.
 *
 * <p><strong>A wiring whose keys are derived claims in a scope of its own</strong> (the
 * Phase 6 → 7 transition): the checkout opens its payment under {@code checkout:<checkoutId>},
 * and in {@code payment.create} any customer could claim that predictable key first through
 * the public command, leaving the payer's confirmation a fingerprint it could never match. The
 * scope is therefore the wiring's decision and a constructor argument, never a default.
 *
 * <h2>The resolutions are authoritative, and a refusal writes nothing</h2>
 *
 * <p>The wallet (owner, account, currency) and the instrument's liveness come from
 * {@link PaymentParticipants} — authoritative rows, never a request's claim — and the amount
 * must be in the wallet's currency: none of these refusals is a committed outcome (the intent
 * machine has no reason vocabulary, deliberately — the attempt's {@code FAILED} carries the
 * enumerated reasons), so each throws with nothing written, the caller's 4xx.
 */
@RequiredArgsConstructor
public final class PaymentCreation {

    /**
     * The public command's idempotency scope (ADR-0004): one command type, one scope. The
     * checkout's derived-key wiring claims in its own ({@code CheckoutService}). Wired with this
     * scope, the claim names its principal ({@code payment.create:<keyedType>:<actorId>},
     * `X-TSK-003`) and this bare value is the scope it moved from, consulted until `X-TSK-028`.
     */
    public static final String IDEMPOTENCY_SCOPE = "payment.create";

    static final String CREATED_EVENT_TYPE = "payments.PaymentIntentCreated";
    static final String PRODUCER = "payments";
    static final String TARGET_TYPE = "payment_intent";

    @NonNull private final IdempotentExecutor executor;
    @NonNull private final PaymentParticipants<Connection> participants;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The claim scope - {@link #IDEMPOTENCY_SCOPE} for the public command, a wiring's own where
     * it derives its keys. Last, so no existing argument moved. It also chooses the claim's
     * shape (`X-TSK-003`): the public command's is scoped to its principal, a wiring's own is
     * claimed as given ({@link #principalScoped()}).
     */
    @NonNull private final String scope;

    /** The caller's ask: their party, their instrument, the amount, and their retry key. */
    public record CreatePaymentCommand(
            UUID callerPartyId,
            UUID paymentMethodId,
            Money amount,
            String idempotencyKey,
            // The book instrument's flag (P7-TSK-011), appended last: true exactly when the
            // payer pays from their own wallet, and then no method is named at all - the
            // intent's XOR, at the command.
            boolean walletInstrument) {
        public CreatePaymentCommand {
            Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
            if (walletInstrument == (paymentMethodId != null)) {
                throw new IllegalArgumentException(
                        "a create command names exactly one instrument: a registered payment"
                                + " method or the payer's own wallet (P7-TSK-011)");
            }
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        }

        /** The registered-method shape every pre-`P7-TSK-011` caller keeps, unchanged. */
        public CreatePaymentCommand(
                UUID callerPartyId, UUID paymentMethodId, Money amount, String idempotencyKey) {
            this(callerPartyId, paymentMethodId, amount, idempotencyKey, false);
        }

        /** The wallet-instrument shape: no method — the payer's wallet is resolved. */
        public static CreatePaymentCommand fromWallet(
                UUID callerPartyId, Money amount, String idempotencyKey) {
            return new CreatePaymentCommand(callerPartyId, null, amount, idempotencyKey, true);
        }

        /** The key and the currency — never the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "CreatePaymentCommand["
                    + (walletInstrument ? "WALLET" : String.valueOf(paymentMethodId))
                    + ", " + amount.currency() + "]";
        }
    }

    /** What the caller learns: the intent, its status, and whether this was a replay. */
    public record CreationResult(
            PaymentIntentId intent, PaymentIntentStatus status, boolean replayed) {}

    /**
     * Creates the intent at most once for its key, or replays the recorded outcome.
     *
     * @throws NoWalletForPaymentException nothing written — the caller's 4xx
     * @throws UnknownPaymentInstrumentException nothing written — the caller's 4xx
     * @throws PaymentCurrencyMismatchException nothing written — the caller's 4xx
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key was used
     *     for a materially different request ({@code INV-IDEM-03})
     */
    public CreationResult create(Connection unitOfWork, CreatePaymentCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(command, "command must not be null");

        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // The authoritative resolutions, before the claim: the fingerprint binds the RESOLVED
        // wallet account (the money's real destination), so it must exist first - and every
        // refusal here writes nothing and consumes no key.
        PaymentParticipants.Wallet wallet =
                participants
                        .walletOwnedBy(
                                unitOfWork, command.callerPartyId(), command.amount().currency())
                        .orElseThrow(NoWalletForPaymentException::new);
        Optional<PaymentParticipants.Wallet> payerWallet = Optional.empty();
        if (command.walletInstrument()) {
            // The book instrument (P7-TSK-011): the payer's OWN wallet, resolved - never a
            // request's claim - and judged in the offer's currency BOTH ways: the credit
            // side's (below, as every instrument) and the debit wallet's own, because the
            // ledger refuses a cross-currency line and the refusal belongs here, before
            // any key is consumed.
            payerWallet =
                    Optional.of(
                            participants
                                    .payerWalletOwnedBy(
                                            unitOfWork,
                                            command.callerPartyId(),
                                            command.amount().currency())
                                    .orElseThrow(NoWalletForPaymentException::new));
            if (!command.amount().currency().equals(payerWallet.get().currency())) {
                throw new PaymentCurrencyMismatchException(
                        command.amount().currency(), payerWallet.get().currency());
            }
        } else {
            // Liveness and ownership of the instrument, EITHER registered kind
            // (P7-TSK-009): the card's token and the bank account's destination are the
            // confirmation's per-kind reads; creation only needs the instrument to be the
            // caller's and live.
            participants
                    .instrumentKindOwnedBy(
                            unitOfWork, command.callerPartyId(), command.paymentMethodId())
                    .orElseThrow(UnknownPaymentInstrumentException::new);
        }
        if (!command.amount().currency().equals(wallet.currency())) {
            throw new PaymentCurrencyMismatchException(
                    command.amount().currency(), wallet.currency());
        }

        IdempotencyKey key = new IdempotencyKey(claimScope(actor), command.idempotencyKey());
        Optional<PaymentParticipants.Wallet> payer = payerWallet;
        // The fingerprint keeps the wiring's scope, byte for byte: a claim made before the
        // move must still match this request's (X-TSK-003).
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(canonicalForm(scope, command, wallet, payer, actor));

        IdempotentExecutor.ExecutionOutcome outcome =
                principalScoped()
                        ? executor.execute(
                                unitOfWork,
                                key,
                                // The old scope is consulted for X-TSK-003's transition and goes with X-TSK-028.
                                key.movedFrom(scope),
                                fingerprint,
                                uow -> accept(uow, command, wallet, payer, actor, correlation))
                        : executor.execute(
                                unitOfWork,
                                key,
                                fingerprint,
                                uow -> accept(uow, command, wallet, payer, actor, correlation));

        String[] body =
                new String(
                                outcome.body()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a recorded creation outcome"
                                                                        + " always carries the"
                                                                        + " id and status")),
                                StandardCharsets.UTF_8)
                        .split("\\|");
        return new CreationResult(
                PaymentIntentId.of(UUID.fromString(body[0])),
                PaymentIntentStatus.valueOf(body[1]),
                outcome.replayed());
    }

    /**
     * Whether this wiring's claim names its principal (`X-TSK-003`): exactly when it is the public
     * command's, whose key a client chooses, so one client's key value must not refuse another's.
     * A wiring with a scope of its own derives its keys from a resource it already authorised
     * (the checkout's {@code checkout:<checkoutId>}), and its claim stays unchanged.
     */
    private boolean principalScoped() {
        return IDEMPOTENCY_SCOPE.equals(scope);
    }

    /**
     * The scope this request claims under: the public command's names the caller by
     * {@link Actor#keyedType()}, never the audit type ({@code IdempotencyScopesKeyThePersonTest});
     * a derived-key wiring's is its own, as given.
     */
    private String claimScope(Actor actor) {
        return principalScoped()
                ? scope + ":" + actor.keyedType().name() + ":" + actor.id()
                : scope;
    }

    /** The acceptance: the row, the audit record and the event, one commit. */
    private CommandResult accept(
            Connection uow,
            CreatePaymentCommand command,
            PaymentParticipants.Wallet wallet,
            Optional<PaymentParticipants.Wallet> payerWallet,
            Actor actor,
            Correlation correlation) {
        PaymentIntent intent =
                command.walletInstrument()
                        ? PaymentIntent.createFromWallet(
                                ids,
                                clock,
                                command.callerPartyId(),
                                wallet.customerId(),
                                payerWallet.orElseThrow().account(),
                                wallet.account(),
                                command.amount())
                        : PaymentIntent.create(
                                ids,
                                clock,
                                command.callerPartyId(),
                                wallet.customerId(),
                                command.paymentMethodId(),
                                wallet.account(),
                                command.amount());
        intents.insert(uow, intent);

        Instant now = Instant.now(clock);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        PaymentsAuditAction.PAYMENT_INTENT_CREATED,
                        TARGET_TYPE,
                        intent.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never an amount (INV-AUD-02).
                        Optional.of(
                                "intent=" + intent.id()
                                        + ", instrument="
                                        + intent.debitAccount()
                                                .map(account -> "WALLET:" + account.value())
                                                .orElseGet(
                                                        () ->
                                                                String.valueOf(
                                                                        intent
                                                                                .paymentMethodId()))
                                        + ", creditAccount=" + intent.creditAccount()
                                        + ", status=" + intent.status())));
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        CREATED_EVENT_TYPE,
                        PaymentEventVersions.of(CREATED_EVENT_TYPE),
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        intent.id(),
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of().with("status", intent.status().name()).toBytes(),
                EventPayload.MEDIA_TYPE);

        String body = intent.id().value() + "|" + intent.status();
        return CommandResult.succeeded(
                StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain"));
    }

    /**
     * The actor and the money's meaning ({@code INV-IDEM-03}); correlation excluded. The
     * instrument segment is the method's identifier, or — for the wallet instrument
     * (`P7-TSK-011`) — {@code WALLET:<resolved account>} in the SAME position, so every
     * pre-existing method fingerprint is byte-identical and a retry that switches
     * instruments meets the conflict it should.
     */
    private static byte[] canonicalForm(
            String scope,
            CreatePaymentCommand command,
            PaymentParticipants.Wallet wallet,
            Optional<PaymentParticipants.Wallet> payerWallet,
            Actor actor) {
        return (scope
                        + "|" + actor.id()
                        + "|" + command.callerPartyId()
                        + "|" + wallet.account().value()
                        + "|"
                        + payerWallet
                                .map(payer -> "WALLET:" + payer.account().value())
                                .orElseGet(() -> String.valueOf(command.paymentMethodId()))
                        + "|" + command.amount().minorUnits()
                        + "|" + command.amount().currency().code()
                        + "|" + command.amount().scale())
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The flow's correlation with the cause resolved — the established idiom. Public since
     * `P5-TSK-013`: the webhook resolver in {@code app} shares the same discipline.
     */
    public static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a payment command must run inside a"
                                                        + " correlation scope: the audit record"
                                                        + " and the event carry the identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
