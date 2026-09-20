package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
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
 * The capture choreography, pinned hermetically (`P5-TSK-010`): dispatch-before-call with the
 * capture reference stored before anything is sent, every non-approved verdict committing its
 * state with <strong>nothing posted</strong>, and convergence everywhere a retry can land.
 *
 * <p><strong>The posting is deliberately a tripwire, not a fake</strong>: {@code PostingService}
 * is final (the transfers precedent — the money path is proven against the real ledger by
 * {@code PaymentCaptureDatabaseTest}), so this suite hands the command a real instance over
 * JDBC stores with no database behind them. Any code path that touches the posting or the
 * chart here explodes — which is exactly the assertion the non-approved verdicts need:
 * {@code CAPTURE_UNKNOWN}, {@code DECLINED} and a refused connection post <strong>nothing</strong>.
 */
@DisplayName("PaymentCapture (P5-TSK-010)")
class PaymentCaptureTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(25_00, EUR);

    private final RecordingRunner runner = new RecordingRunner();
    private final FakeIntentStore intents = new FakeIntentStore();
    private final FakeAttemptStore attempts = new FakeAttemptStore();
    private final FakeEvidenceStore evidence = new FakeEvidenceStore();
    private final ScriptedProvider provider = new ScriptedProvider();
    private final List<AuditRecord> auditTrail = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();

    private PaymentIntent intent;
    private PaymentAttempt authorized;

    private CorrelationContext.Scope correlationScope;

    @BeforeEach
    void fixtures() {
        correlationScope =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("cap-" + UUID.randomUUID())));
        intent =
                PaymentIntent.rehydrate(
                        PaymentIntentId.next(IDS), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), LedgerAccountId.next(IDS), AMOUNT,
                        PaymentIntentStatus.PROCESSING, Instant.now(CLOCK));
        intents.rows.put(intent.id().value(), intent);
        authorized =
                PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), intent.id(),
                        new ProviderIdempotencyReference("auth-" + IDS.next()), null,
                        new ProviderReference("psp-auth-1"), AMOUNT, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED, Instant.now(CLOCK));
        attempts.rows.put(authorized.id().value(), authorized);
    }

    @AfterEach
    void leaveScopes() throws Exception {
        correlationScope.close();
    }

    private PaymentCapture capture() {
        // The tripwire: a REAL PostingService and chart over stores with no database - any
        // touch explodes, so "nothing posted" is structural in this suite (class javadoc).
        return new PaymentCapture(
                runner,
                intents,
                attempts,
                evidence,
                provider,
                new PostingService(
                        new IdempotentExecutor(
                                new com.finapp.platform.idempotency.JdbcIdempotencyRecordStore(),
                                CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                        new com.finapp.ledger.JdbcJournalEntryStore(IDS),
                        (uow, record) -> auditTrail.add(record),
                        new JdbcOutboxWriter(),
                        new com.finapp.ledger.JdbcBalanceProjection(),
                        IDS,
                        CLOCK,
                        PostingObserver.NONE),
                new ChartOfAccounts<>(new JdbcLedgerAccountStore()),
                (uow, record) -> auditTrail.add(record),
                (uow, envelope, payload, mediaType) -> events.add(envelope),
                IDS,
                CLOCK);
    }

    @Test
    @DisplayName("the dispatch commits before the call, with the reference stored and no transaction open")
    void dispatchCommitsBeforeTheCall() {
        provider.answer = ProviderAnswer.indeterminate("garbage".getBytes());
        PaymentCapture.CaptureResult result = capture().capture(authorized.id());

        assertThat(provider.transactionsCommittedAtCall).isEqualTo(1);
        assertThat(provider.activeTransactionAtCall).isFalse();
        assertThat(provider.attemptStatusAtCall)
                .isEqualTo(PaymentAttemptStatus.CAPTURE_DISPATCHED);
        assertThat(provider.referenceStoredAtCall).isTrue();
        assertThat(runner.committed).isEqualTo(2);
        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.CAPTURE_UNKNOWN);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(evidence.payloads).hasSize(1);
        assertThat(events)
                .anyMatch(e -> e.eventType().equals("payments.PaymentStateUnknown"));
    }

    @Test
    @DisplayName("declined and refused-connection fail both rows; ambiguity posts nothing")
    void nonApprovedVerdictsCommitTheirStates() {
        provider.answer = ProviderAnswer.declined("declined-bytes".getBytes());
        PaymentCapture.CaptureResult declined = capture().capture(authorized.id());
        assertThat(declined.attempt()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(declined.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(attempts.single().failureReason()).isEqualTo(PaymentFailureReason.DECLINED);
        assertThat(intents.rows.get(intent.id().value()).status())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(events).anyMatch(e -> e.eventType().equals("payments.PaymentFailed"));

        reset();
        provider.answer = ProviderAnswer.nothingSent();
        PaymentCapture.CaptureResult refused = capture().capture(authorized.id());
        assertThat(refused.attempt()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempts.single().failureReason())
                .isEqualTo(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(evidence.payloads).isEmpty();
    }

    @Test
    @DisplayName("a retried capture converges on every already-moved state, with zero wire calls")
    void aRetriedCaptureConverges() {
        for (PaymentAttemptStatus already : new PaymentAttemptStatus[] {
                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                PaymentAttemptStatus.CAPTURE_UNKNOWN,
                PaymentAttemptStatus.CAPTURED,
                PaymentAttemptStatus.FAILED}) {
            attempts.rows.put(authorized.id().value(), attemptAt(already));
            PaymentCapture.CaptureResult result = capture().capture(authorized.id());
            assertThat(result.converged()).as("%s converges", already).isTrue();
            assertThat(result.attempt()).isEqualTo(already);
        }
        // The stranded and unknown cases are the sweeper's; a retry is never a second
        // provider operation (INV-PAY-04 end to end).
        assertThat(provider.calls).isZero();
    }

    @Test
    @DisplayName("an unauthorized attempt refuses the capture loudly; an unknown id is a defect")
    void refusalsAreLoud() {
        attempts.rows.put(
                authorized.id().value(), attemptAt(PaymentAttemptStatus.AUTH_DISPATCHED));
        assertThatThrownBy(() -> capture().capture(authorized.id()))
                .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
        assertThatThrownBy(() -> capture().capture(PaymentAttemptId.next(IDS)))
                .isInstanceOf(UnknownPaymentException.class);
        assertThat(provider.calls).isZero();
    }

    @Test
    @DisplayName("the loser of the dispatch race converges on the winner's commit")
    void theLoserOfTheDispatchRaceConverges() {
        attempts.refuseNextDispatch = true;
        PaymentCapture.CaptureResult loser = capture().capture(authorized.id());
        assertThat(loser.converged()).isTrue();
        assertThat(loser.attempt()).isEqualTo(PaymentAttemptStatus.CAPTURE_DISPATCHED);
        assertThat(provider.calls).isZero();
    }

    private void reset() {
        intents.rows.put(intent.id().value(), intent);
        attempts.rows.clear();
        attempts.rows.put(authorized.id().value(), authorized);
        evidence.payloads.clear();
        events.clear();
    }

    /** The coherent shape per status, on the aggregate's own doors where possible. */
    private PaymentAttempt attemptAt(PaymentAttemptStatus status) {
        return switch (status) {
            case AUTH_DISPATCHED ->
                    PaymentAttempt.rehydrate(
                            authorized.id(), intent.id(), authorized.authorizationReference(),
                            null, null, null, null, null, null,
                            PaymentAttemptStatus.AUTH_DISPATCHED, authorized.createdAt());
            case CAPTURE_DISPATCHED ->
                    authorized.dispatchCapture(
                            new ProviderIdempotencyReference("cap-" + IDS.next()));
            case CAPTURE_UNKNOWN ->
                    authorized
                            .dispatchCapture(
                                    new ProviderIdempotencyReference("cap-" + IDS.next()))
                            .captureOutcomeUnknown();
            case CAPTURED ->
                    authorized
                            .dispatchCapture(
                                    new ProviderIdempotencyReference("cap-" + IDS.next()))
                            .capture(new ProviderReference("psp-cap-1"), AMOUNT);
            case FAILED -> authorized
                    .dispatchCapture(new ProviderIdempotencyReference("cap-" + IDS.next()))
                    .fail(PaymentFailureReason.DECLINED);
            default -> throw new IllegalArgumentException(status.name());
        };
    }

    // ------------------------------------------------------------------ the recording fakes

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

    private final class ScriptedProvider implements PaymentProvider {
        ProviderAnswer answer = ProviderAnswer.indeterminate();
        int calls;
        int transactionsCommittedAtCall;
        boolean activeTransactionAtCall;
        PaymentAttemptStatus attemptStatusAtCall;
        boolean referenceStoredAtCall;

        @Override
        public String providerName() {
            return "scripted";
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderAnswer capture(CaptureRequest request) {
            calls++;
            transactionsCommittedAtCall = runner.committed;
            activeTransactionAtCall = runner.active;
            PaymentAttempt current = attempts.single();
            attemptStatusAtCall = current.status();
            referenceStoredAtCall =
                    current.captureReference() != null
                            && current.captureReference().value()
                                    .equals(request.reference().value());
            return answer;
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
            PaymentIntent row = rows.get(id.value());
            if (row == null || row.status() != from) {
                return false;
            }
            rows.put(
                    id.value(),
                    PaymentIntent.rehydrate(
                            row.id(), row.partyId(), row.customerId(), row.paymentMethodId(),
                            row.walletAccount(), row.amount(), to, row.createdAt()));
            return true;
        }

        @Override
        public void recordTransition(
                Connection uow, PaymentIntentId id, PaymentIntentStatus from,
                PaymentIntentStatus to, Actor actor, Instant occurredAt) {}
    }

    private static final class FakeAttemptStore implements PaymentAttemptStore<Connection> {

        @Override
        public java.util.Optional<PaymentAttempt> findByOperationReference(
                Connection uow, ProviderIdempotencyReference reference) {
            throw new UnsupportedOperationException("not exercised here");
        }
        final Map<UUID, PaymentAttempt> rows = new HashMap<>();
        boolean refuseNextDispatch;

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
            if (refuseNextDispatch) {
                refuseNextDispatch = false;
                // What the winner committed between this instance's read and write.
                rows.computeIfPresent(
                        id.value(),
                        (key, row) -> row.dispatchCapture(
                                new ProviderIdempotencyReference("cap-winner")));
                return false;
            }
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
                PaymentAttemptStatus to, Actor actor, Instant occurredAt) {}

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
