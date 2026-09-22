package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.payments.EvidenceCipher;
import com.finapp.payments.InstrumentToken;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcProviderEvidenceStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentCancellation;
import com.finapp.payments.PaymentConfirmation;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentCurrencyMismatchException;
import com.finapp.payments.PaymentFailureReason;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.UnknownPaymentException;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0046 against a real PostgreSQL and a real HTTP provider (`P5-TSK-009`): every harness
 * outcome drives its committed state, the crash probe strands the dispatch visibly, a retried
 * confirm converges, ten instances confirming one intent produce one attempt — and the
 * `P1-TSK-026` discipline is <strong>asserted</strong>: the provider is called while this
 * command holds zero database connections, measured at the wire.
 *
 * <p>The participants are a test fake deliberately: the wallet and instrument chains are the
 * established stores' own proven reads, `P5-TSK-011`'s end-to-end drives
 * {@code JdbcPaymentParticipants} over real rows, and this suite's subject is the choreography,
 * the stores and the schema under it.
 */
@Tag("database")
@DisplayName("payment authorization command (P5-TSK-009)")
class PaymentAuthorizationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(10_00, EUR);
    private static final byte[] PSP_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_KEY =
            "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String APPROVED_BODY =
            "  {\"status\" : \"approved\",  \"reference\":\"psp_auth_%s\"}\n";

    private static SimulatedProvider psp;

    private final CountingRunner runner = new CountingRunner();
    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final EvidenceCipher cipher = new EvidenceCipher(EVIDENCE_KEY, 1, new SecureRandom());
    private final JdbcProviderEvidenceStore evidence =
            new JdbcProviderEvidenceStore(cipher, IDS);
    private final FakeParticipants participants = new FakeParticipants();

    private final Actor person = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    private final UUID party = UUID.randomUUID();

    private SecurityContext.Scope actorScope;
    private CorrelationContext.Scope correlationScope;

    @BeforeAll
    static void startProvider() {
        psp = SimulatedProvider.start();
    }

    @AfterAll
    static void stopProvider() {
        psp.close();
    }

    @BeforeEach
    void enterScopes() {
        psp.reset();
        actorScope = SecurityContext.enter(person);
        correlationScope =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("corr-" + UUID.randomUUID())));
    }

    @AfterEach
    void leaveScopes() throws Exception {
        correlationScope.close();
        actorScope.close();
    }

    @Test
    @DisplayName("every harness outcome drives its committed state, with the bytes retained verbatim")
    void everyHarnessOutcomeDrivesItsCommittedState() throws Exception {
        // APPROVED: AUTHORIZED, the intent honestly PROCESSING, the promise recorded, the
        // trim-hostile body retained byte for byte and decrypting to itself (INV-HIST-02).
        String approvedBody = APPROVED_BODY.formatted(UUID.randomUUID());
        psp.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200, approvedBody);
        PaymentIntentId approved = createIntent();
        PaymentConfirmation.ConfirmationResult result =
                confirmation(adapter()).confirm(party, approved);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(result.attempt()).contains(PaymentAttemptStatus.AUTHORIZED);
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt row = attempts.findForIntent(app, approved).orElseThrow();
            assertThat(row.status()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
            assertThat(row.authorizedAmount()).isEqualTo(AMOUNT);
            assertThat(row.authorizationProviderReference()).isNotNull();
            List<byte[]> retained = evidence.payloadsFor(app, row.id());
            assertThat(retained).hasSize(1);
            assertThat(new String(retained.get(0), StandardCharsets.UTF_8))
                    .isEqualTo(approvedBody);
        }

        // DECLINED: the attempt fails with its mapped reason and the intent fails with it.
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"declined\",\"reason\":\"insufficient_funds\"}");
        PaymentIntentId declined = createIntent();
        PaymentConfirmation.ConfirmationResult declinedResult =
                confirmation(adapter()).confirm(party, declined);
        assertThat(declinedResult.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(declinedResult.attempt()).contains(PaymentAttemptStatus.FAILED);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(attempts.findForIntent(app, declined).orElseThrow().failureReason())
                    .isEqualTo(PaymentFailureReason.DECLINED);
            assertThat(intents.findById(app, declined).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.FAILED);
        }

        // Timeout: silence is ambiguity - AUTH_UNKNOWN committed, the intent honestly
        // PROCESSING, the provider HOLDING the request (INV-LIFE-03).
        psp.neverResponds(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);
        PaymentIntentId timedOut = createIntent();
        PaymentConfirmation.ConfirmationResult unknownResult =
                confirmation(adapter(Duration.ofMillis(400))).confirm(party, timedOut);
        assertThat(unknownResult.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(unknownResult.attempt()).contains(PaymentAttemptStatus.AUTH_UNKNOWN);

        // An unknown provider state: indeterminate, never success, evidence retained.
        psp.reset();
        psp.returnsUnknownState(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "reviewing");
        PaymentIntentId unmapped = createIntent();
        assertThat(confirmation(adapter()).confirm(party, unmapped).attempt())
                .contains(PaymentAttemptStatus.AUTH_UNKNOWN);

        // A connection refused before anything was sent is knowledge:
        // FAILED(PROVIDER_UNAVAILABLE), never an unknown to sweep.
        PaymentIntentId refused = createIntent();
        PaymentConfirmation.ConfirmationResult refusedResult =
                confirmation(new SimulatedCardPspAdapter(
                                URI.create("http://127.0.0.1:" + unboundPort()),
                                Duration.ofSeconds(2),
                                PSP_KEY))
                        .confirm(party, refused);
        assertThat(refusedResult.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(refusedResult.attempt()).contains(PaymentAttemptStatus.FAILED);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(attempts.findForIntent(app, refused).orElseThrow().failureReason())
                    .isEqualTo(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("a crash mid-call strands AUTH_DISPATCHED visibly, and nothing else")
    void aCrashMidCallStrandsTheDispatchVisibly() throws Exception {
        PaymentIntentId intent = createIntent();
        PaymentProvider crashing = new CrashingProvider();

        assertThatThrownBy(() -> confirmation(crashing).confirm(party, intent))
                .hasMessage("simulated crash mid-call");

        // The dispatch survived its transaction (the commit-after-call inversion - the
        // backlog's named mutation - leaves NOTHING here); no outcome, no evidence.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(intents.findById(app, intent).orElseThrow().status())
                    .isEqualTo(PaymentIntentStatus.PROCESSING);
            PaymentAttempt stranded = attempts.findForIntent(app, intent).orElseThrow();
            assertThat(stranded.status()).isEqualTo(PaymentAttemptStatus.AUTH_DISPATCHED);
            assertThat(stranded.authorizationReference()).isNotNull();
            assertThat(evidence.payloadsFor(app, stranded.id())).isEmpty();
        }

        // The retry CONVERGES: no second dispatch, no provider call - the stranded case is
        // the sweeper's (ADR-0046 section 3), so a retry can never cause a second provider
        // operation (INV-PAY-04 end to end).
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                APPROVED_BODY.formatted("retry"));
        PaymentConfirmation.ConfirmationResult retry =
                confirmation(adapter()).confirm(party, intent);
        assertThat(retry.converged()).isTrue();
        assertThat(retry.attempt()).contains(PaymentAttemptStatus.AUTH_DISPATCHED);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isZero();
    }

    @Test
    @DisplayName("ten instances confirming one intent produce one attempt, counted")
    void tenInstancesConfirmingProduceOneAttempt() throws Exception {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                APPROVED_BODY.formatted("race"));
        PaymentIntentId intent = createIntent();

        List<Callable<Boolean>> confirms = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            confirms.add(
                    () -> {
                        // Own scopes per simulated instance (ThreadLocals are per thread).
                        SecurityContext.Scope actor = SecurityContext.enter(person);
                        CorrelationContext.Scope correlation =
                                CorrelationContext.enter(
                                        Correlation.startingWith(
                                                CorrelationId.of(
                                                        "race-" + UUID.randomUUID())));
                        try {
                            return confirmation(adapter()).confirm(party, intent).converged();
                        } finally {
                            correlation.close();
                            actor.close();
                        }
                    });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        int convergedCount = 0;
        try {
            for (Future<Boolean> outcome : pool.invokeAll(confirms)) {
                if (outcome.get()) {
                    convergedCount++;
                }
            }
        } finally {
            pool.shutdown();
        }

        // One winner dispatched; nine converged; ONE attempt row and ONE provider operation.
        assertThat(convergedCount).isEqualTo(9);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isEqualTo(1);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?")) {
            count.setObject(1, intent.value());
            try (ResultSet result = count.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("no database connection is held during the provider call (P1-TSK-026, asserted)")
    void noConnectionIsHeldDuringTheCall() {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                APPROVED_BODY.formatted("conn"));
        PaymentIntentId intent = createIntent();

        AtomicInteger heldAtCall = new AtomicInteger(-1);
        PaymentProvider sampling =
                new DelegatingProvider(adapter()) {
                    @Override
                    public ProviderAnswer authorize(AuthorizationRequest request) {
                        heldAtCall.set(runner.held.get());
                        return super.authorize(request);
                    }
                };
        confirmation(sampling).confirm(party, intent);
        assertThat(heldAtCall.get()).isZero();
    }

    @Test
    @DisplayName("the create claim is idempotent, actor-bound, and its refusals write nothing")
    void theCreateClaimIsIdempotentAndItsRefusalsWriteNothing() {
        String key = "create-" + UUID.randomUUID();
        PaymentCreation.CreationResult first = create(key, AMOUNT);
        PaymentCreation.CreationResult replay = create(key, AMOUNT);
        assertThat(replay.intent()).isEqualTo(first.intent());
        assertThat(replay.replayed()).isTrue();

        // The same key for materially different money conflicts (INV-IDEM-03).
        assertThatThrownBy(() -> create(key, Money.ofMinorUnits(99_99, EUR)))
                .isInstanceOf(IdempotencyConflictException.class);

        // The wallet's currency is authoritative; the refusal writes nothing.
        assertThatThrownBy(
                        () ->
                                create(
                                        "usd-" + UUID.randomUUID(),
                                        Money.ofMinorUnits(10_00, CurrencyCode.of("USD"))))
                .isInstanceOf(PaymentCurrencyMismatchException.class);

        // A STRANGER replaying a logged key - same party in the command, same money, a
        // different proven actor - conflicts rather than reading somebody else's outcome:
        // the actor is in the fingerprint (ADR-0004's owning principal, INV-IDEM-03).
        SecurityContext.Scope stranger =
                SecurityContext.enter(
                        new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
        try {
            assertThatThrownBy(() -> create(key, AMOUNT))
                    .isInstanceOf(IdempotencyConflictException.class);
        } finally {
            stranger.close();
        }
    }

    @Test
    @DisplayName("cancel wins only the confirmation window, converges on itself, refuses the rest")
    void cancelWinsOnlyTheWindow() throws Exception {
        PaymentIntentId intent = createIntent();
        PaymentCancellation cancellation = cancellation();

        PaymentCancellation.CancellationResult cancelled =
                runner.inTransaction(uow -> cancellation.cancel(uow, party, intent));
        assertThat(cancelled.status()).isEqualTo(PaymentIntentStatus.CANCELLED);
        assertThat(cancelled.converged()).isFalse();

        // The retry of a lost response converges; a confirm of the cancelled intent is the
        // machine's own refusal.
        assertThat(runner.inTransaction(uow -> cancellation.cancel(uow, party, intent))
                        .converged())
                .isTrue();
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                APPROVED_BODY.formatted("late"));
        assertThatThrownBy(() -> confirmation(adapter()).confirm(party, intent))
                .isInstanceOf(com.finapp.payments.IllegalPaymentIntentTransitionException.class);
        assertThat(psp.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isZero();

        // And a stranger's cancel is the one empty answer - the ownership register's negative
        // test, by name (ADR-0031).
        PaymentIntentId owned = createIntent();
        assertThatThrownBy(
                        () ->
                                runner.inTransaction(
                                        uow ->
                                                cancellation.cancel(
                                                        uow, UUID.randomUUID(), owned)))
                .isInstanceOf(UnknownPaymentException.class);
    }

    @Test
    @DisplayName("the histories carry the audit actor model: the person's act and the platform's")
    void historiesCarryTheActorModel() throws Exception {
        psp.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                APPROVED_BODY.formatted("hist"));
        PaymentIntentId intent = createIntent();
        confirmation(adapter()).confirm(party, intent);

        try (Connection app = DatabaseRoles.application()) {
            // The confirm is the person's act...
            assertThat(historyActors(app, "payment_intent_event", "intent_id", intent.value()))
                    .containsExactly(person.id() + "|" + ActorType.CUSTOMER);
            // ...and the outcome is the platform's (V006's whole reason to exist: 'system' is
            // not a UUID, and the provider's answer has no session).
            UUID attemptId;
            attemptId = attempts.findForIntent(app, intent).orElseThrow().id().value();
            assertThat(historyActors(app, "payment_attempt_event", "attempt_id", attemptId))
                    .containsExactly("system|" + ActorType.SYSTEM);
        }
    }

    @Test
    @DisplayName("a stranger's payment intent is one empty answer (the ownership register's negative test)")
    void aStrangersPaymentIntentIsOneEmptyAnswer() throws Exception {
        PaymentIntentId owned = createIntent();
        try (Connection app = DatabaseRoles.application()) {
            // Not-yours and does-not-exist are indistinguishable (ADR-0031): party_id = ? is
            // the statement's own predicate, and the owner's row is unmoved by the probe.
            assertThat(intents.findOwned(app, owned, UUID.randomUUID())).isEmpty();
            assertThat(intents.findOwned(app, PaymentIntentId.next(IDS), party)).isEmpty();
            assertThat(intents.findOwned(app, owned, party)).isPresent();
        }
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private PaymentIntentId createIntent() {
        return create("key-" + UUID.randomUUID(), AMOUNT).intent();
    }

    private PaymentCreation.CreationResult create(String key, Money amount) {
        PaymentCreation creation =
                new PaymentCreation(
                        new IdempotentExecutor(
                                new JdbcIdempotencyRecordStore(),
                                CLOCK,
                                Duration.ofDays(1),
                                Duration.ofMinutes(5)),
                        participants,
                        intents,
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        IDS,
                        CLOCK);
        return runner.inTransaction(
                uow ->
                        creation.create(
                                uow,
                                new PaymentCreation.CreatePaymentCommand(
                                        party, participants.instrumentId, amount, key)));
    }

    private PaymentConfirmation confirmation(PaymentProvider provider) {
        return new PaymentConfirmation(
                runner,
                intents,
                attempts,
                evidence,
                participants,
                provider,
                outcomes(),
                new JdbcAuditWriter(),
                IDS,
                CLOCK);
    }

    /** The shared outcome component over the real stores (`P5-TSK-013`'s extraction). */
    private com.finapp.payments.PaymentOutcomes outcomes() {
        return new com.finapp.payments.PaymentOutcomes(
                intents,
                attempts,
                new com.finapp.payments.JdbcRefundStore(),
                new com.finapp.ledger.HoldService(
                        new com.finapp.ledger.JdbcLedgerAccountStore(),
                        new com.finapp.ledger.JdbcBalanceDerivation(),
                        new com.finapp.ledger.JdbcHoldStore(),
                        new com.finapp.ledger.JdbcBalanceProjection(),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        IDS,
                        CLOCK),
                new com.finapp.ledger.PostingService(
                        new com.finapp.platform.idempotency.IdempotentExecutor(
                                new com.finapp.platform.idempotency.JdbcIdempotencyRecordStore(),
                                CLOCK,
                                Duration.ofDays(1),
                                Duration.ofMinutes(5)),
                        new com.finapp.ledger.JdbcJournalEntryStore(IDS),
                        new JdbcAuditWriter(),
                        new JdbcOutboxWriter(),
                        new com.finapp.ledger.JdbcBalanceProjection(),
                        IDS,
                        CLOCK,
                        com.finapp.ledger.PostingObserver.NONE),
                new com.finapp.ledger.ChartOfAccounts<>(
                        new com.finapp.ledger.JdbcLedgerAccountStore()),
                // THE PRODUCTION SEAM (P6-TSK-005): the composition production posts through,
                // not the wallet one directly - so "no fee pin, two lines" is proven where it
                // matters. Every payment in this suite is a top-up and falls back.
                new com.finapp.app.merchant.MerchantBoundCaptureComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                new com.finapp.ledger.JdbcLedgerAccountStore(),
                                new com.finapp.ledger.ChartOfAccounts<>(new com.finapp.ledger.JdbcLedgerAccountStore()),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletTopUpComposition(),
                        // No completion: these suites' payments belong to no checkout
                        // session, and the production consumer is wired in CheckoutBeans.
                        landed -> {}),
                // THE REFUND'S MIRROR SEAM (P6-TSK-014), production's own for the same
                // reason: every refund in this suite falls back to Phase 5's two lines, and
                // proving that through the real composition is what makes "byte-identical"
                // a claim about production rather than about a double.
                new com.finapp.app.merchant.MerchantBoundRefundComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                new com.finapp.ledger.JdbcLedgerAccountStore(),
                                new com.finapp.ledger.ChartOfAccounts<>(new com.finapp.ledger.JdbcLedgerAccountStore()),
                                new JdbcOutboxWriter(),
                                IDS),
                        new com.finapp.payments.WalletRefundComposition()),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private PaymentCancellation cancellation() {
        return new PaymentCancellation(intents, new JdbcAuditWriter(), IDS, CLOCK);
    }

    private SimulatedCardPspAdapter adapter() {
        return adapter(Duration.ofSeconds(2));
    }

    private SimulatedCardPspAdapter adapter(Duration timeout) {
        return new SimulatedCardPspAdapter(URI.create(psp.baseUrl()), timeout, PSP_KEY);
    }

    private static int unboundPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("could not find an unbound port", failure);
        }
    }

    private static List<String> historyActors(
            Connection app, String table, String fkColumn, UUID subject) throws SQLException {
        try (PreparedStatement read = app.prepareStatement(
                "SELECT actor_id, actor_type FROM payments." + table
                        + " WHERE " + fkColumn + " = ? ORDER BY id")) {
            read.setObject(1, subject);
            try (ResultSet rows = read.executeQuery()) {
                List<String> actors = new ArrayList<>();
                while (rows.next()) {
                    actors.add(rows.getString(1) + "|" + rows.getString(2));
                }
                return actors;
            }
        }
    }

    /** The production runner's shape over the test roles, counting held connections. */
    private static final class CountingRunner implements TransactionRunner {
        final AtomicInteger held = new AtomicInteger();

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection unitOfWork = DatabaseRoles.application()) {
                held.incrementAndGet();
                try {
                    unitOfWork.setAutoCommit(false);
                    R result = work.apply(unitOfWork);
                    unitOfWork.commit();
                    return result;
                } catch (RuntimeException failure) {
                    unitOfWork.rollback();
                    throw failure;
                } finally {
                    held.decrementAndGet();
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("transaction plumbing failed", failure);
            }
        }
    }

    /** Wallet and instrument as fixtures; the real chain is `P5-TSK-011`'s subject. */
    private final class FakeParticipants implements PaymentParticipants<Connection> {
        final UUID customerId = UUID.randomUUID();
        final UUID instrumentId = UUID.randomUUID();
        final LedgerAccountId wallet = LedgerAccountId.next(IDS);

        @Override
        public Optional<Wallet> walletOwnedBy(Connection uow, UUID callerPartyId) {
            return callerPartyId.equals(party)
                    ? Optional.of(new Wallet(customerId, wallet, EUR))
                    : Optional.empty();
        }

        @Override
        public Optional<InstrumentToken> instrumentOwnedBy(
                Connection uow, UUID callerPartyId, UUID paymentMethodId) {
            return callerPartyId.equals(party) && paymentMethodId.equals(instrumentId)
                    ? Optional.of(InstrumentToken.of("tok_dbtest-4242"))
                    : Optional.empty();
        }
    }

    private static final class CrashingProvider implements PaymentProvider {
        @Override
        public String providerName() {
            return "crashing";
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            throw new IllegalStateException("simulated crash mid-call");
        }

        @Override
        public ProviderAnswer capture(CaptureRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderAnswer refund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueryAnswer query(ProviderIdempotencyReference ourReference) {
            throw new UnsupportedOperationException();
        }
    }

    /** Delegates everything; subclasses observe at the wire. */
    private abstract static class DelegatingProvider implements PaymentProvider {
        private final PaymentProvider delegate;

        private DelegatingProvider(PaymentProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String providerName() {
            return delegate.providerName();
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            return delegate.authorize(request);
        }

        @Override
        public ProviderAnswer capture(CaptureRequest request) {
            return delegate.capture(request);
        }

        @Override
        public ProviderAnswer refund(RefundRequest request) {
            return delegate.refund(request);
        }

        @Override
        public QueryAnswer query(ProviderIdempotencyReference ourReference) {
            return delegate.query(ourReference);
        }
    }
}
