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
                        UUID.randomUUID(), LedgerAccountId.next(IDS), CaptureMode.AUTOMATIC, AMOUNT,
                        PaymentIntentStatus.PROCESSING, Instant.now(CLOCK), null);
        intents.rows.put(intent.id().value(), intent);
        authorized =
                PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), intent.id(),
                        SimulatedCardPspAdapter.RAIL.id(),
                        InteractionModel.TWO_STEP,
                        new ProviderIdempotencyReference("auth-" + IDS.next()), null,
                        new ProviderReference("psp-auth-1"), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED, Instant.now(CLOCK),
                null, null, null, null, null);
        attempts.rows.put(authorized.id().value(), authorized);
    }

    @AfterEach
    void leaveScopes() throws Exception {
        correlationScope.close();
    }

    private PaymentCapture capture() {
        // The tripwire: a REAL PostingService and chart over stores with no database - any
        // touch explodes, so "nothing posted" is structural in this suite (class javadoc).
        PaymentOutcomes outcomes = new PaymentOutcomes(
                        intents,
                        attempts,
                        new com.finapp.payments.JdbcRefundStore(),
                        new com.finapp.ledger.HoldService(
                                new com.finapp.ledger.JdbcLedgerAccountStore(),
                                new com.finapp.ledger.JdbcBalanceDerivation(),
                                new com.finapp.ledger.JdbcHoldStore(),
                                new com.finapp.ledger.JdbcBalanceProjection(),
                                (uow, record) -> {},
                                (uow, envelope, payload, mediaType) -> {},
                                IDS,
                                CLOCK),
                        new PostingService(
                                new IdempotentExecutor(
                                        new com.finapp.platform.idempotency
                                                .JdbcIdempotencyRecordStore(),
                                        CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                                new com.finapp.ledger.JdbcJournalEntryStore(IDS),
                                (uow, record) -> auditTrail.add(record),
                                new JdbcOutboxWriter(),
                                new com.finapp.ledger.JdbcBalanceProjection(),
                                IDS,
                                CLOCK,
                                PostingObserver.NONE),
                        new ChartOfAccounts<>(new JdbcLedgerAccountStore()),
                        // The wallet top-up composition, which is what this suite's intents
                        // are: the two lines PaymentOutcomes used to write inline (P6-TSK-005).
                        new WalletTopUpComposition(),
                        // And its refund mirror (P6-TSK-014), unchanged from Phase 5's pair.
                        new WalletRefundComposition(),
                        (uow, record) -> auditTrail.add(record),
                        (uow, envelope, payload, mediaType) -> events.add(envelope),
                        IDS,
                        CLOCK,
                        PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcUnmatchedConfirmationStore(),
                        new com.finapp.ledger.JdbcLedgerAccountStore(),
                        // No dispute in this suite (P7-TSK-013): a tripwire.
                        UntouchedChargebacks.over(
                                attempts, intents,
                                PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                                IDS, CLOCK),
                        com.finapp.payments.RailOutcomeObserver.NONE,
                        new com.finapp.payments.JdbcSchemeExecutionClaimStore(),
                        // The expectation seam (P8-TSK-004): recorded, not opened -
                        // this suite asserts capture semantics, not the register.
                        new RecordingSettlementExpectations());
        return new PaymentCapture(
                runner,
                intents,
                attempts,
                evidence,
                provider,
                outcomes,
                // The redirect's finisher (P7-TSK-004), over the same fakes.
                new com.finapp.payments.PaymentVoid(
                        runner,
                        intents,
                        attempts,
                        evidence,
                        provider,
                        outcomes,
                        PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                        (uow, record) -> auditTrail.add(record),
                        IDS,
                        CLOCK),
                (uow, record) -> auditTrail.add(record),
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
    @DisplayName("a DECLINED capture on the void-declaring rail RELEASES the promise -"
            + " redirected into the void and concluded VOIDED (P7-TSK-004); a refused"
            + " connection fails both rows; ambiguity posts nothing")
    void nonApprovedVerdictsCommitTheirStates() {
        provider.answer = ProviderAnswer.declined("declined-bytes".getBytes());
        PaymentCapture.CaptureResult declined = capture().capture(authorized.id());
        // The redirect (capability-gated on the STORED rail): the outcome transaction
        // committed VOID_DISPATCHED with its minted reference, this port-owning resolver
        // sent the void, and the release concluded the payment - the authorization is no
        // longer held against the customer for a capture that will never happen.
        assertThat(declined.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        assertThat(declined.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(provider.voidCalls).as("the redirect's send happened").isEqualTo(1);
        assertThat(attempts.single().voidReference()).isNotNull();
        assertThat(attempts.single().voidProviderReference()).isNotNull();
        assertThat(attempts.single().failureReason())
                .as("VOIDED is a release, not a failure - no mapped reason")
                .isNull();
        assertThat(intents.rows.get(intent.id().value()).status())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(events)
                .anyMatch(e -> e.eventType().equals("payments.AuthorizationVoided"));

        // A CAPTURE THAT NEVER LEFT (the Phase 7 -> 8 transition): nothing was captured and
        // the authorization stands, so it redirects into the void exactly as a decline does -
        // the gate found it failing the payment and leaving the hold on the customer's funds.
        reset();
        provider.voidCalls = 0;
        provider.answer = ProviderAnswer.nothingSent();
        PaymentCapture.CaptureResult refused = capture().capture(authorized.id());
        assertThat(refused.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        assertThat(refused.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(provider.voidCalls).as("the redirect's send happened").isEqualTo(1);
        assertThat(attempts.single().failureReason()).isNull();

        // And when the void cannot leave either (the PSP down for both), the row rests
        // VOID_DISPATCHED for the sweeper's permit-free re-send - never FAILED.
        reset();
        provider.answer = ProviderAnswer.nothingSent();
        provider.voidAnswer = ProviderAnswer.nothingSent();
        PaymentCapture.CaptureResult stranded = capture().capture(authorized.id());
        assertThat(stranded.attempt()).isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(stranded.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(evidence.payloads).isEmpty();
    }

    @Test
    @DisplayName("a retried capture converges on every already-moved state, with zero wire calls")
    void aRetriedCaptureConverges() {
        for (PaymentAttemptStatus already : new PaymentAttemptStatus[] {
                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                PaymentAttemptStatus.CAPTURE_UNKNOWN,
                PaymentAttemptStatus.CAPTURED,
                PaymentAttemptStatus.FAILED,
                // A void won the race (the Phase 7 -> 8 transition): the truth, never a throw.
                PaymentAttemptStatus.VOID_DISPATCHED,
                PaymentAttemptStatus.VOID_UNKNOWN,
                PaymentAttemptStatus.VOIDED}) {
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
                            authorized.id(), intent.id(), SimulatedCardPspAdapter.RAIL.id(), InteractionModel.TWO_STEP, authorized.authorizationReference(),
                            null, null, null, null, null, null, null, null,
                            PaymentAttemptStatus.AUTH_DISPATCHED, authorized.createdAt(),
                null, null, null, null, null);
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
            case VOID_DISPATCHED ->
                    authorized.dispatchVoid(
                            new ProviderIdempotencyReference("void-" + IDS.next()));
            case VOID_UNKNOWN ->
                    authorized
                            .dispatchVoid(new ProviderIdempotencyReference("void-" + IDS.next()))
                            .voidOutcomeUnknown();
            case VOIDED ->
                    authorized
                            .dispatchVoid(new ProviderIdempotencyReference("void-" + IDS.next()))
                            .voided(new ProviderReference("psp-void-1"));
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

        /** The declined-capture redirect's send (P7-TSK-004): scripted like the capture. */
        ProviderAnswer voidAnswer =
                ProviderAnswer.approved(new ProviderReference("psp-void-scripted"), "voided".getBytes());
        int voidCalls;

        @Override
        public ProviderAnswer voidAuthorization(VoidRequest request) {
            voidCalls++;
            return voidAnswer;
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
        public boolean anyInFlightCrediting(
                Connection uow, com.finapp.ledger.LedgerAccountId account) {
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
                            row.creditAccount(), CaptureMode.AUTOMATIC, row.amount(), to, row.createdAt(), null));
            return true;
        }

        @Override
        public void recordTransition(
                Connection uow, PaymentIntentId id, PaymentIntentStatus from,
                PaymentIntentStatus to, Actor actor, Instant occurredAt) {}
    }

    private static final class FakeAttemptStore implements PaymentAttemptStore<Connection> {

        @Override
        public java.util.List<PaymentAttempt> pageByStatus(
                Connection unitOfWork,
                PaymentAttemptStatus status,
                java.util.UUID after,
                int limit) {
            // P8-TSK-007: the opening-position backfill's page - not this suite's subject.
            throw new UnsupportedOperationException("no backfill runs in this suite");
        }

        // ------------------------------- the push model (P7-TSK-009): not this suite's subject.

        @Override
        public java.util.Optional<PaymentAttempt> findByEndToEndReference(
                Connection uow, EndToEndReference reference) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.Optional<PaymentAttempt> findBySchemeReference(
                Connection uow, ProviderReference reference) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public boolean openInitiation(
                Connection uow,
                PaymentAttemptId attempt,
                com.finapp.sharedkernel.security.Sensitive<String> handle) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public boolean execute(
                Connection uow,
                PaymentAttemptId attempt,
                PaymentAttemptStatus from,
                ProviderReference schemeReference,
                java.util.Optional<String> settlementCycle) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public boolean failHandleless(
                Connection uow, PaymentAttemptId attempt, PaymentFailureReason reason) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public boolean renewInitiationPermit(
                Connection uow,
                PaymentAttemptId attempt,
                java.time.Instant expected,
                java.time.Instant renewed) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.List<PaymentAttempt> findResolvableInitiations(
                Connection uow, java.time.Instant contactedBefore, int limit) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public UnknownReading awaitingReading(Connection uow) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.Optional<PaymentAttempt> findByOperationReference(
                Connection uow, ProviderIdempotencyReference reference) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public UnknownReading unknownReading(
                Connection uow, java.time.Duration dispatchedBound) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.List<PaymentAttempt> findStrandedAuthorizations(
                Connection uow, java.time.Instant authorizedBefore, int limit) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.List<PaymentAttempt> findSweepable(
                Connection uow, java.time.Instant dispatchedBefore,
                java.time.Instant unknownBefore, int limit) {
            throw new UnsupportedOperationException("not exercised here");
        }

        @Override
        public java.util.Optional<PaymentAttempt> lockById(
                Connection uow, PaymentAttemptId attempt) {
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
        public boolean dispatchVoid(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                ProviderIdempotencyReference reference) {
            return move(id, from, row -> row.dispatchVoid(reference));
        }

        @Override
        public boolean voided(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                ProviderReference reference) {
            return move(id, from, row -> row.voided(reference));
        }

        @Override
        public boolean markVoidUnknown(Connection uow, PaymentAttemptId id) {
            return move(id, PaymentAttemptStatus.VOID_DISPATCHED,
                    PaymentAttempt::voidOutcomeUnknown);
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
        public void appendForWithdrawal(
                Connection uow, WithdrawalId withdrawal, EvidenceKind kind, byte[] payload,
                Instant recordedAt) {
            payloads.add(payload.clone());
        }

        @Override
        public void appendForDisputeResponse(
                Connection uow, DisputeResponseId response, EvidenceKind kind,
                byte[] payload, Instant recordedAt) {
            payloads.add(payload.clone());
        }

        @Override
        public void appendForUnmatched(
                Connection uow, java.util.UUID unmatchedConfirmation, EvidenceKind kind,
                byte[] payload, Instant recordedAt) {
            payloads.add(payload.clone());
        }

        @Override
        public List<byte[]> payloadsFor(Connection uow, PaymentAttemptId attempt) {
            return List.copyOf(payloads);
        }
    }

}
