package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0046's choreography, pinned hermetically (`P5-TSK-009`): the dispatch commits
 * <strong>before</strong> the provider is asked, the call runs <strong>between</strong> the
 * two transactions with none active, every verdict commits exactly its state, and a retry
 * converges without a second dispatch. The recording fakes are the point — the ordering is a
 * property no database assertion can see, and the backlog's named mutation (the dispatch
 * commit moved after the call) fails here first.
 */
@DisplayName("PaymentConfirmation (P5-TSK-009)")
class PaymentConfirmationTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(25_00, EUR);
    private static final Actor PERSON =
            new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);

    private final RecordingRunner runner = new RecordingRunner();
    private final FakeIntentStore intents = new FakeIntentStore();
    private final FakeAttemptStore attempts = new FakeAttemptStore();
    private final FakeEvidenceStore evidence = new FakeEvidenceStore();
    private final ScriptedProvider provider = new ScriptedProvider(runner);
    private final List<AuditRecord> auditTrail = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();

    private final UUID party = UUID.randomUUID();
    private PaymentIntent intent;

    private SecurityContext.Scope actorScope;
    private CorrelationContext.Scope correlationScope;

    @BeforeEach
    void enterScopes() {
        actorScope = SecurityContext.enter(PERSON);
        correlationScope =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("corr-" + UUID.randomUUID())));
        intent =
                PaymentIntent.create(
                        IDS, CLOCK, party, UUID.randomUUID(), UUID.randomUUID(),
                        LedgerAccountId.next(IDS), AMOUNT);
        intents.rows.put(intent.id().value(), intent);
    }

    @AfterEach
    void leaveScopes() throws Exception {
        correlationScope.close();
        actorScope.close();
    }

    private PaymentConfirmation confirmation() {
        return new PaymentConfirmation(
                runner,
                intents,
                attempts,
                evidence,
                new FakeParticipants(),
                provider,
                outcomes(),
                (uow, record) -> auditTrail.add(record),
                IDS,
                CLOCK);
    }

    /**
     * The shared outcome component over the same fakes (`P5-TSK-013`'s extraction) — with a
     * REAL {@code PostingService} and chart over stores with no database: any posting touch
     * explodes, so "authorization posts nothing" (ADR-0048) is structural in this suite too,
     * the {@code PaymentCaptureTest} tripwire inherited by the extraction.
     */
    private PaymentOutcomes outcomes() {
        return new PaymentOutcomes(
                intents,
                attempts,
                new com.finapp.ledger.PostingService(
                        new com.finapp.platform.idempotency.IdempotentExecutor(
                                new com.finapp.platform.idempotency.JdbcIdempotencyRecordStore(),
                                CLOCK,
                                java.time.Duration.ofDays(1),
                                java.time.Duration.ofMinutes(5)),
                        new com.finapp.ledger.JdbcJournalEntryStore(IDS),
                        (uow, record) -> auditTrail.add(record),
                        new com.finapp.platform.outbox.JdbcOutboxWriter(),
                        new com.finapp.ledger.JdbcBalanceProjection(),
                        IDS,
                        CLOCK,
                        com.finapp.ledger.PostingObserver.NONE),
                new com.finapp.ledger.ChartOfAccounts<>(
                        new com.finapp.ledger.JdbcLedgerAccountStore()),
                (uow, record) -> auditTrail.add(record),
                (uow, envelope, payload, mediaType) -> events.add(envelope),
                IDS,
                CLOCK);
    }

    @Test
    @DisplayName("the dispatch commits before the call, and the call holds no transaction")
    void dispatchCommitsBeforeTheCall() {
        provider.answer = ProviderAnswer.approved(
                new ProviderReference("psp-auth-1"), "body".getBytes());
        confirmation().confirm(party, intent.id());

        // The choreography's order, recorded where it happened: Tx1 committed, THEN the call
        // with no transaction active, THEN Tx2. The commit-after-call inversion - the named
        // mutation - cannot satisfy this.
        assertThat(provider.transactionsCommittedAtCall).isEqualTo(1);
        assertThat(provider.activeTransactionAtCall).isFalse();
        assertThat(runner.committed).isEqualTo(2);
        // And what Tx1 committed is the dispatch: the attempt existed, AUTH_DISPATCHED, with
        // its reference minted, before the provider saw anything (INV-PAY-04).
        assertThat(provider.attemptStatusAtCall).isEqualTo(PaymentAttemptStatus.AUTH_DISPATCHED);
        assertThat(provider.requestReference).isNotNull();
    }

    @Test
    @DisplayName("every verdict commits exactly its state, and received bytes are retained")
    void everyVerdictCommitsItsState() {
        provider.answer = ProviderAnswer.approved(
                new ProviderReference("psp-auth-ok"), "approved-bytes".getBytes());
        PaymentConfirmation.ConfirmationResult approved =
                confirmation().confirm(party, intent.id());
        assertThat(approved.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(approved.attempt()).contains(PaymentAttemptStatus.AUTHORIZED);
        assertThat(attempts.single().status()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
        assertThat(attempts.single().authorizedAmount()).isEqualTo(AMOUNT);
        assertThat(evidence.payloads).hasSize(1);
        assertThat(events)
                .anyMatch(e -> e.eventType().equals("payments.PaymentAuthorized"));

        // DECLINED: the attempt fails with its mapped reason and the intent fails with it.
        reset(ProviderAnswer.declined("declined-bytes".getBytes()));
        PaymentConfirmation.ConfirmationResult declined =
                confirmation().confirm(party, intent.id());
        assertThat(declined.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(declined.attempt()).contains(PaymentAttemptStatus.FAILED);
        assertThat(attempts.single().failureReason()).isEqualTo(PaymentFailureReason.DECLINED);
        assertThat(intents.rows.get(intent.id().value()).status())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(events).anyMatch(e -> e.eventType().equals("payments.PaymentFailed"));

        // NOTHING_SENT: a refused connection is knowledge (nothing arrived, so no evidence).
        reset(ProviderAnswer.nothingSent());
        PaymentConfirmation.ConfirmationResult refused =
                confirmation().confirm(party, intent.id());
        assertThat(refused.attempt()).contains(PaymentAttemptStatus.FAILED);
        assertThat(attempts.single().failureReason())
                .isEqualTo(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(evidence.payloads).isEmpty();

        // INDETERMINATE: we do not know is the answer, and it commits (INV-LIFE-03) - the
        // intent honestly PROCESSING, the received garbage still retained (INV-HIST-02).
        reset(ProviderAnswer.indeterminate("garbage".getBytes()));
        PaymentConfirmation.ConfirmationResult unknown =
                confirmation().confirm(party, intent.id());
        assertThat(unknown.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(unknown.attempt()).contains(PaymentAttemptStatus.AUTH_UNKNOWN);
        assertThat(evidence.payloads).hasSize(1);
        assertThat(events)
                .anyMatch(e -> e.eventType().equals("payments.PaymentStateUnknown"));
    }

    @Test
    @DisplayName("a retried confirm converges: no second dispatch, no provider call")
    void aRetriedConfirmConverges() {
        provider.answer = ProviderAnswer.indeterminate();
        confirmation().confirm(party, intent.id());
        assertThat(provider.calls).isEqualTo(1);

        // The retry: the intent is PROCESSING with a stranded-unknown attempt. Converge -
        // the resolution is the sweeper's, never a retry's second operation (INV-PAY-04).
        PaymentConfirmation.ConfirmationResult retry =
                confirmation().confirm(party, intent.id());
        assertThat(retry.converged()).isTrue();
        assertThat(retry.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(retry.attempt()).contains(PaymentAttemptStatus.AUTH_UNKNOWN);
        assertThat(provider.calls).isEqualTo(1);
        assertThat(attempts.rows).hasSize(1);
    }

    @Test
    @DisplayName("refusals write nothing: unknown intent, stranger's intent, detached instrument, cancelled intent")
    void refusalsWriteNothing() {
        assertThatThrownBy(
                        () -> confirmation().confirm(party, PaymentIntentId.next(IDS)))
                .isInstanceOf(UnknownPaymentException.class);
        assertThatThrownBy(
                        () -> confirmation().confirm(UUID.randomUUID(), intent.id()))
                .as("not-yours and does-not-exist are one answer")
                .isInstanceOf(UnknownPaymentException.class);

        // The instrument detached between create and confirm: refused with the intent
        // untouched, still awaiting confirmation.
        FakeParticipants noInstrument = new FakeParticipants();
        noInstrument.instrumentPresent = false;
        PaymentConfirmation withoutInstrument =
                new PaymentConfirmation(
                        runner, intents, attempts, evidence, noInstrument, provider,
                        outcomes(),
                        (uow, record) -> auditTrail.add(record),
                        IDS, CLOCK);
        assertThatThrownBy(() -> withoutInstrument.confirm(party, intent.id()))
                .isInstanceOf(UnknownPaymentInstrumentException.class);
        assertThat(intents.rows.get(intent.id().value()).status())
                .isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        assertThat(provider.calls).isZero();

        // A cancelled intent refuses confirmation as the machine's own 409.
        intents.rows.put(intent.id().value(),
                intents.rows.get(intent.id().value()).cancel());
        assertThatThrownBy(() -> confirmation().confirm(party, intent.id()))
                .isInstanceOf(IllegalPaymentIntentTransitionException.class);
        assertThat(provider.calls).isZero();
    }

    @Test
    @DisplayName("the loser of the transition race converges on the winner's commit")
    void theLoserOfTheRaceConverges() {
        provider.answer = ProviderAnswer.approved(
                new ProviderReference("psp-auth-w"), "w".getBytes());
        // The store refuses the conditional transition once - another instance's write landed
        // between this instance's read and write - and the re-read finds PROCESSING.
        intents.refuseNextTransition = true;
        PaymentAttempt winners =
                PaymentAttempt.create(IDS, CLOCK, intent.id(),
                        new ProviderIdempotencyReference("auth-" + IDS.next()));
        attempts.rows.put(winners.id().value(), winners);

        PaymentConfirmation.ConfirmationResult loser =
                confirmation().confirm(party, intent.id());
        assertThat(loser.converged()).isTrue();
        assertThat(loser.attempt()).contains(PaymentAttemptStatus.AUTH_DISPATCHED);
        assertThat(provider.calls).isZero();
        assertThat(attempts.rows).hasSize(1);
    }

    private void reset(ProviderAnswer next) {
        intents.rows.put(intent.id().value(), intent);
        attempts.rows.clear();
        evidence.payloads.clear();
        events.clear();
        provider.answer = next;
    }

    // ------------------------------------------------------------------ the recording fakes

    /** Counts commits and exposes whether a transaction is active — the ordering oracle. */
    private static final class RecordingRunner implements TransactionRunner {
        int committed;
        boolean active;

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            active = true;
            try {
                R result = work.apply(null);
                committed++;
                return result;
            } finally {
                active = false;
            }
        }
    }

    private final class FakeParticipants implements PaymentParticipants<Connection> {
        boolean instrumentPresent = true;

        @Override
        public Optional<Wallet> walletOwnedBy(Connection uow, UUID callerPartyId) {
            throw new UnsupportedOperationException("confirm never resolves the wallet");
        }

        @Override
        public Optional<InstrumentToken> instrumentOwnedBy(
                Connection uow, UUID callerPartyId, UUID paymentMethodId) {
            return instrumentPresent
                    ? Optional.of(InstrumentToken.of("tok_test-4242"))
                    : Optional.empty();
        }
    }

    /** Scripted answer plus the wire-time observations the ordering assertion needs. */
    private final class ScriptedProvider implements PaymentProvider {
        private final RecordingRunner observedRunner;
        ProviderAnswer answer = ProviderAnswer.indeterminate();
        int calls;
        int transactionsCommittedAtCall;
        boolean activeTransactionAtCall;
        PaymentAttemptStatus attemptStatusAtCall;
        ProviderIdempotencyReference requestReference;

        private ScriptedProvider(RecordingRunner observedRunner) {
            this.observedRunner = observedRunner;
        }

        @Override
        public String providerName() {
            return "scripted";
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            calls++;
            transactionsCommittedAtCall = observedRunner.committed;
            activeTransactionAtCall = observedRunner.active;
            attemptStatusAtCall = attempts.single().status();
            requestReference = request.reference();
            return answer;
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

    private static final class FakeIntentStore implements PaymentIntentStore<Connection> {
        final Map<UUID, PaymentIntent> rows = new HashMap<>();
        boolean refuseNextTransition;

        @Override
        public java.util.List<PaymentIntent> listFor(Connection uow, UUID partyId) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public void insert(Connection uow, PaymentIntent fresh) {
            rows.put(fresh.id().value(), fresh);
        }

        @Override
        public Optional<PaymentIntent> findById(Connection uow, PaymentIntentId id) {
            return Optional.ofNullable(rows.get(id.value()));
        }

        @Override
        public Optional<PaymentIntent> findOwned(
                Connection uow, PaymentIntentId id, UUID partyId) {
            return findById(uow, id).filter(row -> row.partyId().equals(partyId));
        }

        @Override
        public boolean transition(
                Connection uow, PaymentIntentId id, PaymentIntentStatus from,
                PaymentIntentStatus to) {
            if (refuseNextTransition) {
                refuseNextTransition = false;
                // What the winner committed while this instance was between read and write.
                rows.computeIfPresent(id.value(), (key, row) -> row.confirm());
                return false;
            }
            PaymentIntent row = rows.get(id.value());
            if (row == null || row.status() != from) {
                return false;
            }
            PaymentIntent moved =
                    PaymentIntent.rehydrate(
                            row.id(), row.partyId(), row.customerId(), row.paymentMethodId(),
                            row.walletAccount(), row.amount(), to, row.createdAt());
            rows.put(id.value(), moved);
            return true;
        }

        @Override
        public void recordTransition(
                Connection uow, PaymentIntentId id, PaymentIntentStatus from,
                PaymentIntentStatus to, Actor actor, Instant occurredAt) {
            // History is the database suite's subject.
        }
    }

    private static final class FakeAttemptStore implements PaymentAttemptStore<Connection> {

        @Override
        public java.util.Optional<PaymentAttempt> findByOperationReference(
                Connection uow, ProviderIdempotencyReference reference) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.List<PaymentAttempt> findSweepable(
                Connection uow, java.time.Instant dispatchedBefore,
                java.time.Instant unknownBefore, int limit) {
            throw new UnsupportedOperationException("not exercised here");
        }
        final Map<UUID, PaymentAttempt> rows = new HashMap<>();

        PaymentAttempt single() {
            assertThat(rows).hasSize(1);
            return rows.values().iterator().next();
        }

        @Override
        public void insert(Connection uow, PaymentAttempt fresh) {
            rows.put(fresh.id().value(), fresh);
        }

        @Override
        public Optional<PaymentAttempt> findForIntent(Connection uow, PaymentIntentId intent) {
            return rows.values().stream()
                    .filter(row -> row.intentId().equals(intent))
                    .findFirst();
        }

        @Override
        public Optional<PaymentAttempt> findById(Connection uow, PaymentAttemptId id) {
            return Optional.ofNullable(rows.get(id.value()));
        }

        @Override
        public boolean dispatchCapture(
                Connection uow, PaymentAttemptId id, ProviderIdempotencyReference reference) {
            return move(id, PaymentAttemptStatus.AUTHORIZED,
                    row -> row.dispatchCapture(reference));
        }

        @Override
        public boolean capture(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                ProviderReference reference, Money amount) {
            return move(id, from, row -> row.capture(reference, amount));
        }

        @Override
        public boolean markCaptureUnknown(Connection uow, PaymentAttemptId id) {
            return move(id, PaymentAttemptStatus.CAPTURE_DISPATCHED,
                    PaymentAttempt::captureOutcomeUnknown);
        }

        @Override
        public boolean authorize(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                ProviderReference reference, Money amount) {
            return move(id, from, row -> row.authorize(reference, amount));
        }

        @Override
        public boolean fail(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                PaymentFailureReason reason) {
            return move(id, from, row -> row.fail(reason));
        }

        @Override
        public boolean markAuthUnknown(Connection uow, PaymentAttemptId id) {
            return move(id, PaymentAttemptStatus.AUTH_DISPATCHED,
                    PaymentAttempt::authorizationOutcomeUnknown);
        }

        @Override
        public void recordTransition(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                PaymentAttemptStatus to, Actor actor, Instant occurredAt) {
            // History is the database suite's subject.
        }

        private boolean move(
                PaymentAttemptId id, PaymentAttemptStatus from,
                Function<PaymentAttempt, PaymentAttempt> door) {
            PaymentAttempt row = rows.get(id.value());
            if (row == null || row.status() != from) {
                return false;
            }
            rows.put(id.value(), door.apply(row));
            return true;
        }
    }

    private static final class FakeEvidenceStore implements ProviderEvidenceStore<Connection> {
        final List<byte[]> payloads = new ArrayList<>();

        @Override
        public void append(
                Connection uow, Optional<PaymentAttemptId> attempt, Optional<RefundId> refund,
                EvidenceKind kind, byte[] payload, Instant recordedAt) {
            payloads.add(payload.clone());
        }

        @Override
        public List<byte[]> payloadsFor(Connection uow, PaymentAttemptId attempt) {
            return List.copyOf(payloads);
        }
    }
}
