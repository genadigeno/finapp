package com.finapp.app.payments;

import com.finapp.app.telemetry.PaymentMeters;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.CallbackAppliers;
import com.finapp.payments.CallbackHint;
import com.finapp.payments.CallbackHintResolution;
import com.finapp.payments.CallbackHintStore;
import com.finapp.payments.CaptureMode;
import com.finapp.payments.CardCallbackInquiry;
import com.finapp.payments.ClearingAnswer;
import com.finapp.payments.DisputeAnswer;
import com.finapp.payments.DisputeNotice;
import com.finapp.payments.DisputeNotifications;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.ExchangeAnswer;
import com.finapp.payments.ExecutionAnswer;
import com.finapp.payments.HintedInquiry;
import com.finapp.payments.HintedOutcome;
import com.finapp.payments.InitiationAnswer;
import com.finapp.payments.InstantCallbackInquiry;
import com.finapp.payments.InteractionModel;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentClearing;
import com.finapp.payments.PaymentFailureReason;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PushAnswer;
import com.finapp.payments.PushInquiryAnswer;
import com.finapp.payments.PushRail;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.RailId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.UnmatchedConfirmations;
import com.finapp.payments.WebhookSignature;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalOutcomes;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.WithdrawalStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxRecordStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/**
 * A hermetic world for the two callback doors (`P15-TSK-036`): real doors, real signature, real inbox consumer, the real
 * hinted inquiries and hint resolution, over in-memory rows. What is recorded rather than run is the seam every outcome
 * passes through ({@link CallbackAppliers}), because the assertion that matters is exactly what reached it: a forged
 * callback must leave it empty, and a genuine one must hand it the inquiry's words and never the callback's.
 *
 * <p>Every provider call asserts that no transaction is open (ADR-0046), so no test has to remember to.
 */
final class CallbackHintWorld implements AutoCloseable {

    static final Clock CLOCK = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CurrencyCode USD = CurrencyCode.of("USD");
    static final Money AMOUNT = Money.ofMinorUnits(25_00, USD);
    static final RailId CARD = SimulatedCardPspAdapter.RAIL.id();
    static final RailId INSTANT = SimulatedInstantSchemeAdapter.RAIL.id();
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /** One retained provider statement: its kind and what it was addressed to. */
    record Retained(EvidenceKind kind, Optional<PaymentAttemptId> attempt, Optional<RefundId> refund,
                    Optional<WithdrawalId> withdrawal, Optional<UUID> parking, byte[] bytes) {
        boolean unattributed() {
            return attempt.isEmpty() && refund.isEmpty() && withdrawal.isEmpty() && parking.isEmpty();
        }
    }

    final Map<UUID, PaymentAttempt> attempts = new HashMap<>();
    final Map<UUID, PaymentIntent> intents = new HashMap<>();
    final Map<UUID, Refund> refunds = new HashMap<>();
    final Map<UUID, Withdrawal> withdrawals = new HashMap<>();
    final List<Retained> retained = new ArrayList<>();
    final List<CallbackHint> hintRows = new ArrayList<>();
    final Map<UUID, HintedOutcome> concluded = new HashMap<>();
    final Set<String> inboxRecords = new HashSet<>();
    boolean inboxContended;

    /** The questions asked of a provider, in order. */
    final List<String> asked = new ArrayList<>();

    /** What reached the appliers, one line per call, in order. */
    final List<String> applied = new ArrayList<>();

    final Map<String, QueryAnswer> queries = new HashMap<>();
    final Map<String, ClearingAnswer> clearings = new HashMap<>();
    final Map<String, DisputeAnswer> disputes = new HashMap<>();
    final Map<String, PushInquiryAnswer> initiations = new HashMap<>();
    final Map<String, PushInquiryAnswer> transfers = new HashMap<>();
    final Map<String, PushInquiryAnswer> returns = new HashMap<>();
    final Map<String, ExecutionAnswer> executions = new HashMap<>();

    /** When set, every inquiry throws: an adapter defect, the worst failure the port can show. */
    boolean inquiriesThrow;

    /** Whether the rail can park; only a chart missing the currency says no. */
    boolean parkable = true;

    private int openTransactions;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PaymentMeters meters = new PaymentMeters(registry, SimulatedCardPspAdapter.NAME);
    private final CorrelationContext.Scope correlation =
            CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("hint-" + UUID.randomUUID())));

    private final Connection connection =
            (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("this world has no database: " + method.getName());
                    });

    final TransactionRunner runner = new TransactionRunner() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            openTransactions++;
            try {
                return work.apply(connection);
            } finally {
                openTransactions--;
            }
        }
    };

    final PaymentAttemptStore<Connection> attemptStore = stores(PaymentAttemptStore.class, new AttemptReads());
    final PaymentIntentStore<Connection> intentStore = stores(PaymentIntentStore.class, new IntentReads());
    final RefundStore<Connection> refundStore = stores(RefundStore.class, new RefundReads());
    final WithdrawalStore<Connection> withdrawalStore = stores(WithdrawalStore.class, new WithdrawalReads());
    final ProviderEvidenceStore<Connection> evidenceStore = stores(ProviderEvidenceStore.class, new EvidenceWrites());
    final CallbackHintStore<Connection> hintStore = new Hints();
    final InboxConsumer<Connection> inbox = new InboxConsumer<>(new InboxRecords(), CLOCK, Duration.ofDays(1));
    final CallbackAppliers appliers = new RecordingAppliers();
    final PaymentProvider psp = new ScriptedPsp();
    final PushRail scheme = new ScriptedScheme();

    final CardCallbackInquiry cardInquiry =
            new CardCallbackInquiry(attemptStore, intentStore, refundStore, evidenceStore, psp, appliers, runner, CLOCK);
    final InstantCallbackInquiry instantInquiry =
            new InstantCallbackInquiry(
                    attemptStore, intentStore, refundStore, withdrawalStore, evidenceStore, scheme, appliers, INSTANT,
                    runner, CLOCK);
    final CallbackHintResolution hintResolution =
            new CallbackHintResolution(
                    hintStore,
                    List.<HintedInquiry>of(cardInquiry, instantInquiry),
                    new CallbackHintResolution.Config(
                            Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofDays(7), 50),
                    IDS,
                    runner);

    private final WebhookSignature signature = new WebhookSignature(KEY, Duration.ofMinutes(5), CLOCK);
    final PaymentWebhookService cardDoor =
            new PaymentWebhookService(
                    signature, evidenceStore, attemptStore, refundStore, meters, inbox, new ObjectMapper(), CLOCK, runner,
                    cardInquiry, hintStore, hintResolution, CARD);
    final InstantCallbackService instantDoor =
            new InstantCallbackService(
                    signature, evidenceStore, attemptStore, meters, inbox, new ObjectMapper(), CLOCK, runner, instantInquiry,
                    hintStore, hintResolution, INSTANT, refundStore, withdrawalStore);

    @Override
    public void close() {
        correlation.close();
    }

    // ------------------------------------------------------------------ deliveries

    void deliverCard(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        cardDoor.deliver(bytes, timestamp, signature.sign(timestamp, bytes));
    }

    void deliverInstant(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        instantDoor.deliver(bytes, timestamp, signature.sign(timestamp, bytes));
    }

    static String event() {
        return "evt_" + IDS.next();
    }

    static String cardBody(String operation, String status, String reference) {
        return "{\"eventId\":\"" + event() + "\",\"operation\":\"" + operation + "\",\"status\":\"" + status
                + "\",\"reference\":\"" + reference + "\"}";
    }

    static String clearingBody(String operation) {
        return "{\"eventId\":\"" + event() + "\",\"operation\":\"" + operation
                + "\",\"status\":\"cleared\",\"arn\":\"arn-forged\",\"networkTransactionId\":\"ntid-forged\"}";
    }

    static String disputeBody(String operation, String dispute) {
        return "{\"eventId\":\"" + event() + "\",\"operation\":\"" + operation + "\",\"status\":\"disputed\",\"dispute\":\""
                + dispute + "\",\"stage\":\"lost\",\"reasonCode\":\"fraudulent\",\"amountMinor\":\"999999\","
                + "\"currency\":\"USD\",\"scale\":2}";
    }

    static String instantBody(String reference, String status, String scheme) {
        return "{\"eventId\":\"" + event() + "\",\"reference\":\"" + reference + "\",\"status\":\"" + status
                + "\",\"schemeReference\":\"" + scheme + "\",\"settlementCycle\":\"cycle-forged\",\"amount\":\"999.00\","
                + "\"currency\":\"USD\"}";
    }

    double webhookCount(String outcome) {
        return registry.get("finapp.payments.webhook").tag("outcome", outcome).counter().count();
    }

    long retained(EvidenceKind kind) {
        return retained.stream().filter(row -> row.kind() == kind).count();
    }

    // ------------------------------------------------------------------ rows

    PaymentIntent intent() {
        PaymentIntent intent =
                PaymentIntent.rehydrate(
                        PaymentIntentId.next(IDS), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        LedgerAccountId.next(IDS), CaptureMode.AUTOMATIC, AMOUNT, PaymentIntentStatus.PROCESSING,
                        Instant.now(CLOCK), null);
        intents.put(intent.id().value(), intent);
        return intent;
    }

    private PaymentAttempt store(PaymentAttempt attempt) {
        attempts.put(attempt.id().value(), attempt);
        return attempt;
    }

    private static ProviderIdempotencyReference ours() {
        return new ProviderIdempotencyReference("ours-" + IDS.next());
    }

    PaymentAttempt authDispatched() {
        return store(PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), intent().id(), CARD, InteractionModel.TWO_STEP, ours(), null, null, null, null,
                null, null, null, null, PaymentAttemptStatus.AUTH_DISPATCHED, Instant.now(CLOCK), null, null, null, null,
                null));
    }

    PaymentAttempt captureDispatched() {
        return store(PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), intent().id(), CARD, InteractionModel.TWO_STEP, ours(), ours(),
                new ProviderReference("psp-auth"), AMOUNT, null, null, null, null, null,
                PaymentAttemptStatus.CAPTURE_DISPATCHED, Instant.now(CLOCK), null, null, null, null, null));
    }

    PaymentAttempt captured(String pspReference) {
        return store(PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), intent().id(), CARD, InteractionModel.TWO_STEP, ours(), ours(),
                new ProviderReference("psp-auth"), AMOUNT, new ProviderReference(pspReference), AMOUNT, null, null, null,
                PaymentAttemptStatus.CAPTURED, Instant.now(CLOCK), null, null, null, null, null));
    }

    PaymentAttempt failedAtCapture() {
        return store(PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), intent().id(), CARD, InteractionModel.TWO_STEP, ours(), ours(),
                new ProviderReference("psp-auth"), AMOUNT, null, null, null, null, PaymentFailureReason.NEVER_RECEIVED,
                PaymentAttemptStatus.FAILED, Instant.now(CLOCK), null, null, null, null, null));
    }

    PaymentAttempt voidDispatched() {
        return store(PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), intent().id(), CARD, InteractionModel.TWO_STEP, ours(), null,
                new ProviderReference("psp-auth"), AMOUNT, null, null, ours(), null, null,
                PaymentAttemptStatus.VOID_DISPATCHED, Instant.now(CLOCK), null, null, null, null, null));
    }

    private PaymentAttempt push(PaymentAttemptStatus status, ProviderReference scheme, PaymentFailureReason reason) {
        Instant now = Instant.now(CLOCK);
        return store(PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), intent().id(), INSTANT, InteractionModel.PUSH, null, null, null, null, null,
                null, null, null, reason, status, now,
                new EndToEndReference(IDS.next().toString().replace("-", "")), null, scheme, null, now));
    }

    PaymentAttempt awaitingPayer() {
        return push(PaymentAttemptStatus.AWAITING_PAYER, null, null);
    }

    PaymentAttempt pushFailed() {
        return push(PaymentAttemptStatus.FAILED, null, PaymentFailureReason.DECLINED);
    }

    PaymentAttempt executed(String scheme) {
        return push(PaymentAttemptStatus.EXECUTED, new ProviderReference(scheme), null);
    }

    Refund refundOn(PaymentAttempt attempt, RefundStatus status, String pspReference) {
        Refund refund =
                Refund.rehydrate(
                        RefundId.next(IDS), attempt.id(), AMOUNT, "a customer's refund", HoldId.next(IDS), ours(),
                        pspReference == null ? null : new ProviderReference(pspReference), status, Instant.now(CLOCK));
        refunds.put(refund.id().value(), refund);
        return refund;
    }

    Withdrawal withdrawal(WithdrawalStatus status) {
        Instant now = Instant.now(CLOCK);
        Withdrawal withdrawal =
                Withdrawal.rehydrate(
                        WithdrawalId.next(IDS), UUID.randomUUID(), UUID.randomUUID(), LedgerAccountId.next(IDS),
                        UUID.randomUUID(), new ProviderReference("dest-1"), AMOUNT,
                        new EndToEndReference(IDS.next().toString().replace("-", "")), INSTANT, status,
                        Optional.empty(), Optional.empty(), Optional.empty(), HoldId.next(IDS), now, now, Optional.empty(),
                        Optional.empty(), Optional.empty());
        withdrawals.put(withdrawal.id().value(), withdrawal);
        return withdrawal;
    }

    // ------------------------------------------------------------------ plumbing

    @SuppressWarnings("unchecked")
    private static <S> S stores(Class<?> type, Object implementation) {
        return (S) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(implementation, args);
            }
            for (Method candidate : implementation.getClass().getDeclaredMethods()) {
                if (candidate.getName().equals(method.getName())
                        && candidate.getParameterCount() == method.getParameterCount()) {
                    candidate.setAccessible(true);
                    try {
                        return candidate.invoke(implementation, args);
                    } catch (InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                }
            }
            throw new UnsupportedOperationException("a callback door reached " + type.getSimpleName() + "." + method.getName());
        });
    }

    private void inquiry(String question) {
        if (openTransactions != 0) {
            throw new AssertionError("a provider was called inside a database transaction (ADR-0046): " + question);
        }
        asked.add(question);
        if (inquiriesThrow) {
            throw new IllegalStateException("the adapter broke");
        }
    }

    @SuppressWarnings("unused") // Reached reflectively through stores().
    private final class AttemptReads {
        Optional<PaymentAttempt> findByOperationReference(Connection uow, ProviderIdempotencyReference reference) {
            return attempts.values().stream()
                    .filter(row -> reference.equals(row.authorizationReference())
                            || reference.equals(row.captureReference())
                            || reference.equals(row.voidReference()))
                    .findFirst();
        }

        Optional<PaymentAttempt> findByEndToEndReference(Connection uow, EndToEndReference reference) {
            return attempts.values().stream().filter(row -> reference.equals(row.endToEndReference())).findFirst();
        }

        Optional<PaymentAttempt> findById(Connection uow, PaymentAttemptId id) {
            return Optional.ofNullable(attempts.get(id.value()));
        }

        Optional<PaymentAttempt> lockById(Connection uow, PaymentAttemptId id) {
            return Optional.ofNullable(attempts.get(id.value()));
        }
    }

    @SuppressWarnings("unused")
    private final class IntentReads {
        Optional<PaymentIntent> findById(Connection uow, PaymentIntentId id) {
            return Optional.ofNullable(intents.get(id.value()));
        }
    }

    @SuppressWarnings("unused")
    private final class RefundReads {
        Optional<Refund> findByOperationReference(Connection uow, ProviderIdempotencyReference reference) {
            return refunds.values().stream()
                    .filter(row -> reference.equals(row.providerIdempotencyReference()))
                    .findFirst();
        }

        Optional<RefundStore.LockedRefund> lockForOutcome(Connection uow, RefundId id) {
            return Optional.ofNullable(refunds.get(id.value()))
                    .map(refund -> new RefundStore.LockedRefund(refund, Instant.now(CLOCK)));
        }
    }

    @SuppressWarnings("unused")
    private final class WithdrawalReads {
        Optional<Withdrawal> findByEndToEndReference(Connection uow, EndToEndReference reference) {
            return withdrawals.values().stream().filter(row -> reference.equals(row.reference())).findFirst();
        }

        Optional<Withdrawal> findForUpdate(Connection uow, WithdrawalId id) {
            return Optional.ofNullable(withdrawals.get(id.value()));
        }
    }

    @SuppressWarnings("unused")
    private final class EvidenceWrites {
        void append(
                Connection uow, Optional<PaymentAttemptId> attempt, Optional<RefundId> refund, EvidenceKind kind,
                byte[] payload, Instant at) {
            retained.add(new Retained(kind, attempt, refund, Optional.empty(), Optional.empty(), payload));
        }

        void appendForWithdrawal(Connection uow, WithdrawalId withdrawal, EvidenceKind kind, byte[] payload, Instant at) {
            retained.add(new Retained(kind, Optional.empty(), Optional.empty(), Optional.of(withdrawal), Optional.empty(), payload));
        }

        void appendForUnmatched(Connection uow, UUID parking, EvidenceKind kind, byte[] payload, Instant at) {
            retained.add(new Retained(kind, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(parking), payload));
        }
    }

    private final class Hints implements CallbackHintStore<Connection> {
        @Override
        public CallbackHint insert(
                Connection uow, CallbackHint.Kind kind, Optional<PaymentAttemptId> attempt, String askedAbout,
                Duration firstAsk) {
            CallbackHint hint = new CallbackHint(IDS.next(), kind, attempt, askedAbout, Instant.now(CLOCK).plus(firstAsk));
            hintRows.add(hint);
            return hint;
        }

        @Override
        public List<CallbackHint> findDue(Connection uow, int batchSize) {
            return hintRows.stream().filter(hint -> !concluded.containsKey(hint.id())).limit(batchSize).toList();
        }

        @Override
        public boolean renewPermit(Connection uow, CallbackHint hint, Duration retryAfter) {
            return !concluded.containsKey(hint.id());
        }

        @Override
        public boolean conclude(Connection uow, UUID hint, HintedOutcome conclusion) {
            return concluded.putIfAbsent(hint, conclusion) == null;
        }

        @Override
        public int expire(Connection uow, Duration giveUpAfter, int batchSize) {
            return 0;
        }
    }

    private final class InboxRecords implements InboxRecordStore<Connection> {
        @Override
        public RecordOutcome record(
                Connection uow, InboxKey key, String messageType, CorrelationId correlationId, Instant processedAt,
                Duration retention) {
            if (inboxContended) {
                return RecordOutcome.CONTENDED;
            }
            return inboxRecords.add(key.consumer() + "|" + key.dedupeKey())
                    ? RecordOutcome.RECORDED
                    : RecordOutcome.ALREADY_PROCESSED;
        }
    }

    private final class RecordingAppliers implements CallbackAppliers {

        private String of(Optional<ProviderReference> reference) {
            return reference.map(ProviderReference::value).orElse("-");
        }

        @Override
        public PaymentOutcomes.Applied applyAuthorization(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Money promised, Correlation c) {
            applied.add("authorization " + verdict + " " + of(reference));
            return new PaymentOutcomes.Applied(PaymentIntentStatus.PROCESSING, from, true, false);
        }

        @Override
        public PaymentOutcomes.Applied applyCapture(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, LedgerAccountId wallet, Money amount,
                Correlation c) {
            applied.add("capture " + verdict + " " + of(reference));
            return new PaymentOutcomes.Applied(PaymentIntentStatus.PROCESSING, from, true, false);
        }

        @Override
        public PaymentOutcomes.Applied applyVoid(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Correlation c) {
            applied.add("void " + verdict + " " + of(reference));
            return new PaymentOutcomes.Applied(PaymentIntentStatus.PROCESSING, from, true, false);
        }

        @Override
        public PaymentOutcomes.RefundApplied applyRefund(
                Connection uow, PaymentIntentId intent, Refund refund, RefundStatus from, ProviderAnswer.Verdict verdict,
                Optional<ProviderReference> reference, LedgerAccountId wallet, Correlation c) {
            applied.add("refund " + verdict + " " + of(reference));
            return new PaymentOutcomes.RefundApplied(from, true);
        }

        @Override
        public PaymentOutcomes.Applied applyExecution(
                Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
                PushInquiryAnswer.Verdict verdict, Optional<ProviderReference> scheme, Optional<String> cycle,
                LedgerAccountId credit, Money amount, Optional<Money> executed, Correlation c) {
            applied.add("execution " + verdict + " " + of(scheme) + " " + executed.map(Money::minorUnits).orElse(-1L)
                    + " from " + from);
            return new PaymentOutcomes.Applied(PaymentIntentStatus.PROCESSING, from, true, false);
        }

        @Override
        public WithdrawalOutcomes.Applied applyWithdrawal(
                Connection uow, Withdrawal locked, PushInquiryAnswer answer, Instant bound, Correlation c) {
            applied.add("withdrawal " + answer.verdict() + " " + of(answer.schemeReference()));
            return new WithdrawalOutcomes.Applied(locked.status(), locked.status().isResolvable());
        }

        @Override
        public PaymentClearing.Outcome recordClearing(
                Connection uow, PaymentAttempt attempt, ProviderReference acquirer, ProviderReference network, Correlation c) {
            applied.add("clearing " + acquirer.value() + " " + network.value());
            return PaymentClearing.Outcome.RECORDED;
        }

        @Override
        public DisputeNotifications.Outcome applyDispute(
                Connection uow, PaymentAttempt attempt, DisputeNotice notice, Correlation c) {
            applied.add("dispute " + notice.reference().value() + " " + notice.stage() + " " + notice.amount().minorUnits());
            return DisputeNotifications.Outcome.OPENED;
        }

        @Override
        public boolean canPark(Connection uow, RailId rail, CurrencyCode currency) {
            return parkable;
        }

        @Override
        public UnmatchedConfirmations.Parked park(Connection uow, UnmatchedConfirmations.Parking parking) {
            applied.add("park " + parking.schemeReference().value() + " " + parking.amount().minorUnits());
            return new UnmatchedConfirmations.Parked(true, Optional.of(IDS.next()));
        }
    }

    private final class ScriptedPsp implements PaymentProvider {
        @Override
        public String providerName() {
            return SimulatedCardPspAdapter.NAME;
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public ProviderAnswer capture(CaptureRequest request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public ProviderAnswer refund(RefundRequest request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public ProviderAnswer voidAuthorization(VoidRequest request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public QueryAnswer query(ProviderIdempotencyReference ourReference) {
            inquiry("query " + ourReference.value());
            return queries.getOrDefault(ourReference.value(), QueryAnswer.indeterminate());
        }

        @Override
        public ClearingAnswer queryClearing(ProviderIdempotencyReference ourReference) {
            inquiry("clearing " + ourReference.value());
            return clearings.getOrDefault(ourReference.value(), ClearingAnswer.indeterminate());
        }

        @Override
        public DisputeAnswer queryDispute(ProviderReference disputeReference) {
            inquiry("dispute " + disputeReference.value());
            return disputes.getOrDefault(disputeReference.value(), DisputeAnswer.indeterminate());
        }
    }

    private final class ScriptedScheme implements PushRail {
        @Override
        public String schemeName() {
            return SimulatedInstantSchemeAdapter.NAME;
        }

        @Override
        public ExchangeAnswer exchange(GrantExchange request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public PushAnswer send(CreditTransfer request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public PushInquiryAnswer inquire(EndToEndReference ourReference) {
            inquiry("transfer " + ourReference.value());
            return transfers.getOrDefault(ourReference.value(), PushInquiryAnswer.indeterminate());
        }

        @Override
        public InitiationAnswer initiate(PayInInitiation request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public PushInquiryAnswer inquireInitiation(EndToEndReference ourReference) {
            inquiry("initiation " + ourReference.value());
            return initiations.getOrDefault(ourReference.value(), PushInquiryAnswer.indeterminate());
        }

        @Override
        public PushAnswer sendReturn(ReturnPayment request) {
            throw new AssertionError("a callback must send nothing");
        }

        @Override
        public PushInquiryAnswer inquireReturn(EndToEndReference ourReference) {
            inquiry("return " + ourReference.value());
            return returns.getOrDefault(ourReference.value(), PushInquiryAnswer.indeterminate());
        }

        @Override
        public ExecutionAnswer inquireExecution(ProviderReference schemeReference) {
            inquiry("execution " + schemeReference.value());
            return executions.getOrDefault(schemeReference.value(), ExecutionAnswer.indeterminate());
        }
    }
}
