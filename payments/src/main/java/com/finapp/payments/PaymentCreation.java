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

/**
 * The intent-creation command ({@code P5-TSK-009}, ADR-0045): acceptance, never execution —
 * one transaction commits the intent at {@code REQUIRES_CONFIRMATION}, its audit record and
 * {@code payments.PaymentIntentCreated}, and <strong>nothing is dispatched and nothing
 * posts</strong> (ADR-0048). Confirmation is a separate act, which is the window that gives
 * {@code CANCELLED} its producer.
 *
 * <h2>The claim is at the financial boundary</h2>
 *
 * <p>Scope {@code payment.create}, the caller's key, and a fingerprint binding <strong>the
 * actor</strong> and the money's meaning — party, wallet account, instrument, amount, currency,
 * scale (the backlog's list; {@code INV-IDEM-03}'s subjects, the actor per ADR-0004). The
 * stored body replays {@code intentId|status}.
 *
 * <h2>The resolutions are authoritative, and a refusal writes nothing</h2>
 *
 * <p>The wallet (owner, account, currency) and the instrument's liveness come from
 * {@link PaymentParticipants} — authoritative rows, never a request's claim — and the amount
 * must be in the wallet's currency: none of these refusals is a committed outcome (the intent
 * machine has no reason vocabulary, deliberately — the attempt's {@code FAILED} carries the
 * enumerated reasons), so each throws with nothing written, the caller's 4xx.
 */
public final class PaymentCreation {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    public static final String IDEMPOTENCY_SCOPE = "payment.create";

    static final String CREATED_EVENT_TYPE = "payments.PaymentIntentCreated";
    static final String PRODUCER = "payments";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "payment_intent";

    private final IdempotentExecutor executor;
    private final PaymentParticipants<Connection> participants;
    private final PaymentIntentStore<Connection> intents;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public PaymentCreation(
            IdempotentExecutor executor,
            PaymentParticipants<Connection> participants,
            PaymentIntentStore<Connection> intents,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.participants = Objects.requireNonNull(participants, "participants must not be null");
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The caller's ask: their party, their instrument, the amount, and their retry key. */
    public record CreatePaymentCommand(
            UUID callerPartyId, UUID paymentMethodId, Money amount, String idempotencyKey) {
        public CreatePaymentCommand {
            Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
            Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        }

        /** The key and the currency — never the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "CreatePaymentCommand[" + paymentMethodId + ", " + amount.currency() + "]";
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
                        .walletOwnedBy(unitOfWork, command.callerPartyId())
                        .orElseThrow(NoWalletForPaymentException::new);
        participants
                .instrumentOwnedBy(
                        unitOfWork, command.callerPartyId(), command.paymentMethodId())
                .orElseThrow(UnknownPaymentInstrumentException::new);
        if (!command.amount().currency().equals(wallet.currency())) {
            throw new PaymentCurrencyMismatchException(
                    command.amount().currency(), wallet.currency());
        }

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(canonicalForm(command, wallet, actor));

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow -> accept(uow, command, wallet, actor, correlation));

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

    /** The acceptance: the row, the audit record and the event, one commit. */
    private CommandResult accept(
            Connection uow,
            CreatePaymentCommand command,
            PaymentParticipants.Wallet wallet,
            Actor actor,
            Correlation correlation) {
        PaymentIntent intent =
                PaymentIntent.create(
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
                                        + ", instrument=" + intent.paymentMethodId()
                                        + ", wallet=" + intent.walletAccount()
                                        + ", status=" + intent.status())));
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        CREATED_EVENT_TYPE,
                        EVENT_VERSION,
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

    /** The actor and the money's meaning ({@code INV-IDEM-03}); correlation excluded. */
    private static byte[] canonicalForm(
            CreatePaymentCommand command, PaymentParticipants.Wallet wallet, Actor actor) {
        return (IDEMPOTENCY_SCOPE
                        + "|" + actor.id()
                        + "|" + command.callerPartyId()
                        + "|" + wallet.account().value()
                        + "|" + command.paymentMethodId()
                        + "|" + command.amount().minorUnits()
                        + "|" + command.amount().currency().code()
                        + "|" + command.amount().scale())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The flow's correlation with the cause resolved — the established idiom. */
    static Correlation resolvedCorrelation() {
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
