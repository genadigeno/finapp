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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The void choreography, pinned hermetically (`P7-TSK-004`, the {@code PaymentCaptureTest}
 * idiom): the capability judged from the STORED rail's declaration before anything is
 * written or sent ({@code INV-REV-03}, {@code INV-RAIL-01}), dispatch-before-call with OUR
 * reference stored first ({@code INV-PAY-04}), every verdict committing exactly its state,
 * a refused connection concluding <strong>nothing</strong> — the row stays
 * {@code VOID_DISPATCHED} and any instance's permit-free re-send releases it — and
 * convergence everywhere a retry or a racing resolver can land.
 *
 * <p><strong>The posting tripwire</strong> (the capture suite's): a void moves no money on
 * ANY verdict — a release has nothing to post — so the whole suite runs over a real
 * {@code PostingService} whose stores have no database. Any code path that touches the
 * ledger here explodes.
 */
@DisplayName("PaymentVoid (P7-TSK-004)")
class PaymentVoidTest {

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
    private final ScriptedProvider provider = new ScriptedProvider();
    private final List<AuditRecord> auditTrail = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();

    private final UUID party = UUID.randomUUID();
    private PaymentIntent intent;
    private PaymentAttempt authorized;

    private SecurityContext.Scope actorScope;
    private CorrelationContext.Scope correlationScope;

    @BeforeEach
    void fixtures() {
        actorScope = SecurityContext.enter(PERSON);
        correlationScope =
                CorrelationContext.enter(
                        Correlation.startingWith(
                                CorrelationId.of("void-" + UUID.randomUUID())));
        intent =
                PaymentIntent.rehydrate(
                        PaymentIntentId.next(IDS), party, UUID.randomUUID(),
                        UUID.randomUUID(), LedgerAccountId.next(IDS), CaptureMode.AUTOMATIC,
                        AMOUNT, PaymentIntentStatus.PROCESSING, Instant.now(CLOCK), null);
        intents.rows.put(intent.id().value(), intent);
        authorized =
                PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), intent.id(),
                        SimulatedCardPspAdapter.RAIL.id(),
                        InteractionModel.TWO_STEP,
                        new ProviderIdempotencyReference("auth-" + IDS.next()), null,
                        new ProviderReference("psp-auth-1"), AMOUNT, null, null, null, null,
                        null, PaymentAttemptStatus.AUTHORIZED, Instant.now(CLOCK),
                null, null, null, null, null);
        attempts.rows.put(authorized.id().value(), authorized);
    }

    @AfterEach
    void leaveScopes() throws Exception {
        correlationScope.close();
        actorScope.close();
    }

    private PaymentVoid voids() {
        return voidsOn(PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL)));
    }

    private PaymentVoid voidsOn(PaymentRails rails) {
        // The tripwire: a REAL PostingService and chart over stores with no database - any
        // touch explodes, so "a void posts nothing" is structural (class javadoc).
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
                        new WalletTopUpComposition(),
                        new WalletRefundComposition(),
                        (uow, record) -> auditTrail.add(record),
                        (uow, envelope, payload, mediaType) -> events.add(envelope),
                        IDS,
                        CLOCK,
                        PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL)),
                new JdbcUnmatchedConfirmationStore(),
                        new com.finapp.ledger.JdbcLedgerAccountStore(),
                        // No dispute in this suite (P7-TSK-013): a tripwire.
                        UntouchedChargebacks.over(
                                attempts, intents, PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL)),
                                IDS, CLOCK),
                        com.finapp.payments.RailOutcomeObserver.NONE,
                        new com.finapp.payments.JdbcSchemeExecutionClaimStore());
        return new PaymentVoid(
                runner,
                intents,
                attempts,
                evidence,
                provider,
                outcomes,
                rails,
                (uow, record) -> auditTrail.add(record),
                IDS,
                CLOCK);
    }

    /** The one declared rail, redeclared WITHOUT the void: INV-REV-03's negative. */
    private static PaymentRails voidlessDirectory() {
        return PaymentRails.of(List.of(new PaymentRail(
                SimulatedCardPspAdapter.RAIL.id(),
                2,
                new RailCapabilities(
                        InteractionModel.TWO_STEP,
                        RailCapabilities.Finality.REVOCABLE_UNTIL_DISPUTE_WINDOW_ENDS,
                        Set.of(),
                        RailCapabilities.RefundMode.PROVIDER_REFUND,
                        RailCapabilities.SettlementModel.DEFERRED_VIA_CLEARING,
                        Optional.empty(),
                        RailCapabilities.DisputeModel.CARD_SCHEME_CHARGEBACKS,
                        Optional.empty(),
                        Map.of(),
                        Optional.of(com.finapp.ledger.AccountPurpose.SETTLEMENT_CLEARING)))));
    }

    private PaymentAttempt attemptAt(PaymentAttemptStatus status) {
        PaymentAttempt at = switch (status) {
            case AUTHORIZED -> authorized;
            case CAPTURE_DISPATCHED ->
                    authorized.dispatchCapture(new ProviderIdempotencyReference("cap-1"));
            case CAPTURED ->
                    authorized
                            .dispatchCapture(new ProviderIdempotencyReference("cap-1"))
                            .capture(new ProviderReference("psp-cap-1"), AMOUNT);
            case VOID_DISPATCHED ->
                    authorized.dispatchVoid(new ProviderIdempotencyReference("void-prior"));
            case VOID_UNKNOWN ->
                    authorized
                            .dispatchVoid(new ProviderIdempotencyReference("void-prior"))
                            .voidOutcomeUnknown();
            case VOIDED ->
                    authorized
                            .dispatchVoid(new ProviderIdempotencyReference("void-prior"))
                            .voided(new ProviderReference("psp-void-done"));
            default -> throw new IllegalArgumentException("not shaped here: " + status);
        };
        return at;
    }

    @Test
    @DisplayName("the dispatch commits before the call: OUR reference stored, no transaction"
            + " open, the authorization's provider reference on the wire")
    void dispatchCommitsBeforeTheCall() {
        provider.answer = ProviderAnswer.indeterminate("garbage".getBytes());
        PaymentVoid.VoidResult result =
                voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());

        // Tx1 (the dispatch) and the finisher's read both committed before the wire.
        assertThat(provider.transactionsCommittedAtCall).isEqualTo(2);
        assertThat(provider.activeTransactionAtCall).isFalse();
        assertThat(provider.attemptStatusAtCall)
                .isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(provider.referenceStoredAtCall).isTrue();
        assertThat(provider.authorizationOnTheWire).isEqualTo("psp-auth-1");
        assertThat(runner.committed).isEqualTo(3);
        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOID_UNKNOWN);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(result.acting()).isTrue();
    }

    @Test
    @DisplayName("an approved void releases the promise: VOIDED with the acknowledgement,"
            + " the intent FAILED, the event announced, the dispatch audited")
    void anApprovedVoidReleasesThePromise() {
        provider.answer =
                ProviderAnswer.approved(new ProviderReference("psp-void-1"), "ok".getBytes());
        PaymentVoid.VoidResult result =
                voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(result.converged()).isFalse();
        assertThat(result.acting()).isTrue();
        PaymentAttempt row = attempts.single();
        assertThat(row.voidReference().value()).startsWith("void-");
        assertThat(row.voidProviderReference().value()).isEqualTo("psp-void-1");
        assertThat(row.failureReason())
                .as("VOIDED is a release, not a failure - no mapped reason")
                .isNull();
        assertThat(row.capturedAmount()).as("nothing was ever taken").isNull();
        assertThat(intents.rows.get(intent.id().value()).status())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(events)
                .anyMatch(e -> e.eventType().equals("payments.AuthorizationVoided"));
        assertThat(evidence.payloads).hasSize(1);
        // The dispatch audit: the customer's act speaks for itself - no reason - and the
        // summary names identifiers and the rail, never an amount (INV-AUD-02).
        AuditRecord dispatched = auditTrail.stream()
                .filter(record -> record.operation()
                        == PaymentsAuditAction.PAYMENT_VOID_DISPATCHED)
                .findFirst()
                .orElseThrow();
        assertThat(dispatched.reason()).isEmpty();
        assertThat(dispatched.changeSummary().orElseThrow())
                .contains("rail=" + SimulatedCardPspAdapter.RAIL.id().value())
                .contains("reference=" + row.voidReference().value());
    }

    @Test
    @DisplayName("the operator's reason travels verbatim into the dispatch audit (INV-AUD-03)")
    void theOperatorsReasonTravelsVerbatim() {
        provider.answer =
                ProviderAnswer.approved(new ProviderReference("psp-void-1"), "ok".getBytes());
        PaymentVoid.VoidResult result =
                voids().voidAuthorized(
                        Optional.empty(), intent.id(),
                        Optional.of("suspected fraud on the card"));

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        AuditRecord dispatched = auditTrail.stream()
                .filter(record -> record.operation()
                        == PaymentsAuditAction.PAYMENT_VOID_DISPATCHED)
                .findFirst()
                .orElseThrow();
        assertThat(dispatched.reason()).contains("suspected fraud on the card");
    }

    @Test
    @DisplayName("the operator door without a reason is refused by the domain itself - nothing"
            + " written, nothing sent (P7-DOC-001, INV-AUD-03)")
    void theOperatorDoorRequiresAReason() {
        provider.answer =
                ProviderAnswer.approved(new ProviderReference("psp-void-1"), "ok".getBytes());
        for (Optional<String> missing : List.of(Optional.<String>empty(), Optional.of("   "))) {
            assertThatThrownBy(
                            () -> voids().voidAuthorized(Optional.empty(), intent.id(), missing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reasoned");
        }
        assertThat(runner.committed).as("nothing written").isZero();
        assertThat(provider.voidCalls).as("nothing sent").isZero();
        assertThat(attempts.single().status()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
        assertThat(auditTrail).isEmpty();
    }

    @Test
    @DisplayName("an ambiguous answer commits VOID_UNKNOWN - no acknowledgement invented,"
            + " the intent untouched, the bytes retained")
    void anAmbiguousAnswerCommitsUnknown() {
        provider.answer = ProviderAnswer.indeterminate("late-bytes".getBytes());
        PaymentVoid.VoidResult result =
                voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOID_UNKNOWN);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        PaymentAttempt row = attempts.single();
        assertThat(row.status()).isEqualTo(PaymentAttemptStatus.VOID_UNKNOWN);
        assertThat(row.voidProviderReference()).isNull();
        assertThat(evidence.payloads).hasSize(1);
    }

    @Test
    @DisplayName("a declined void is the third FAILED shape: the reason mapped, OUR"
            + " reference kept, no acknowledgement - and the intent concluded")
    void aDeclinedVoidFailsBothRows() {
        provider.answer = ProviderAnswer.declined("no".getBytes());
        PaymentVoid.VoidResult result =
                voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());

        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        PaymentAttempt row = attempts.single();
        assertThat(row.failureReason()).isEqualTo(PaymentFailureReason.DECLINED);
        assertThat(row.voidReference()).isNotNull();
        assertThat(row.voidProviderReference()).isNull();
    }

    @Test
    @DisplayName("a refused connection concludes NOTHING: the row stays VOID_DISPATCHED and"
            + " any instance's permit-free re-send releases it (the V009 asymmetry)")
    void aRefusedConnectionConcludesNothing() {
        provider.answer = ProviderAnswer.nothingSent();
        PaymentVoid.VoidResult refused =
                voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());

        // No conclusion, structurally: nothing left this process, the promise stands, and
        // the machine holds the row where any instance can finish it.
        assertThat(refused.attempt()).isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(refused.intent()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(refused.acting()).isFalse();
        assertThat(attempts.single().status())
                .isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(evidence.payloads).isEmpty();

        // The re-send: no permit consulted, the same stored reference on the wire - the
        // provider releases a released promise idempotently, so re-sending converges.
        provider.answer =
                ProviderAnswer.approved(new ProviderReference("psp-void-2"), "ok".getBytes());
        PaymentVoid.VoidResult finished = voids().completeDispatched(authorized.id());
        assertThat(provider.voidCalls).isEqualTo(2);
        assertThat(provider.referenceStoredAtCall).isTrue();
        assertThat(finished.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        assertThat(finished.intent()).isEqualTo(PaymentIntentStatus.FAILED);
    }

    @Test
    @DisplayName("a rail declaring no reversal refuses BEFORE anything is written or sent"
            + " (INV-REV-03 judged on the stored rail's declaration)")
    void theCapabilityIsJudgedBeforeAnythingElse() {
        provider.answer =
                ProviderAnswer.approved(new ProviderReference("psp-void-1"), "ok".getBytes());
        assertThatThrownBy(
                        () ->
                                voidsOn(voidlessDirectory())
                                        .voidAuthorized(
                                                Optional.of(party), intent.id(),
                                                Optional.empty()))
                .isInstanceOf(ReversalNotSupportedException.class);

        assertThat(runner.committed).as("Tx1 rolled back - nothing written").isZero();
        assertThat(provider.voidCalls).as("nothing sent").isZero();
        assertThat(attempts.single().status()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
        assertThat(attempts.single().voidReference()).isNull();
        assertThat(auditTrail).isEmpty();
    }

    @Test
    @DisplayName("the customer door is findOwned: a stranger's intent and an unknown one"
            + " are the same 404, with nothing written")
    void aStrangersIntentIsUnknown() {
        assertThatThrownBy(
                        () ->
                                voids().voidAuthorized(
                                        Optional.of(UUID.randomUUID()), intent.id(),
                                        Optional.empty()))
                .isInstanceOf(UnknownPaymentException.class);
        assertThat(runner.committed).isZero();
        assertThat(attempts.single().status()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("a capture already in flight, or a terminal, refuses the void loudly with"
            + " nothing written - the machine's truth, not a silent no-op")
    void anUnvoidableStateRefusesLoudly() {
        for (PaymentAttemptStatus already : new PaymentAttemptStatus[] {
                PaymentAttemptStatus.CAPTURE_DISPATCHED, PaymentAttemptStatus.CAPTURED}) {
            attempts.rows.put(authorized.id().value(), attemptAt(already));
            assertThatThrownBy(
                            () ->
                                    voids().voidAuthorized(
                                            Optional.of(party), intent.id(),
                                            Optional.empty()))
                    .as("%s refuses the void", already)
                    .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
            assertThat(attempts.single().status()).isEqualTo(already);
        }
        assertThat(provider.voidCalls).isZero();
    }

    @Test
    @DisplayName("a retried void converges on every void-stage state with zero wire calls")
    void aRetriedVoidConverges() {
        for (PaymentAttemptStatus already : new PaymentAttemptStatus[] {
                PaymentAttemptStatus.VOID_DISPATCHED,
                PaymentAttemptStatus.VOID_UNKNOWN,
                PaymentAttemptStatus.VOIDED}) {
            attempts.rows.put(authorized.id().value(), attemptAt(already));
            PaymentVoid.VoidResult result =
                    voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());
            assertThat(result.converged()).as("%s converges", already).isTrue();
            assertThat(result.acting()).isFalse();
            assertThat(result.attempt()).isEqualTo(already);
        }
        // A stranded VOID_DISPATCHED belongs to the sweeper's re-send leg, not a customer
        // retry: a retry is never a second provider operation (INV-PAY-04 end to end).
        assertThat(provider.voidCalls).isZero();
    }

    @Test
    @DisplayName("losing the conditional out of AUTHORIZED converges on a sibling void, and"
            + " is told the truth when the capture chain won")
    void aLostDispatchRaceConvergesOrTellsTheTruth() {
        attempts.voidWinsNextVoidDispatch = true;
        PaymentVoid.VoidResult sibling =
                voids().voidAuthorized(Optional.of(party), intent.id(), Optional.empty());
        assertThat(sibling.converged()).isTrue();
        assertThat(sibling.attempt()).isEqualTo(PaymentAttemptStatus.VOID_DISPATCHED);

        attempts.rows.put(authorized.id().value(), authorized);
        attempts.captureWinsNextVoidDispatch = true;
        assertThatThrownBy(
                        () ->
                                voids().voidAuthorized(
                                        Optional.of(party), intent.id(), Optional.empty()))
                .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
        assertThat(provider.voidCalls).as("neither loser sent anything").isZero();
    }

    @Test
    @DisplayName("the finisher converges on a row another resolver already concluded")
    void theFinisherConvergesOnAConcludedRow() {
        attempts.rows.put(authorized.id().value(), attemptAt(PaymentAttemptStatus.VOIDED));
        intents.transition(null, intent.id(), PaymentIntentStatus.PROCESSING,
                PaymentIntentStatus.FAILED);
        PaymentVoid.VoidResult result = voids().completeDispatched(authorized.id());
        assertThat(result.converged()).isTrue();
        assertThat(result.attempt()).isEqualTo(PaymentAttemptStatus.VOIDED);
        assertThat(result.intent()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(provider.voidCalls).isZero();
    }

    // ------------------------------------------------------------------ the fakes

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
        int voidCalls;
        int transactionsCommittedAtCall;
        boolean activeTransactionAtCall;
        PaymentAttemptStatus attemptStatusAtCall;
        boolean referenceStoredAtCall;
        String authorizationOnTheWire;

        @Override
        public String providerName() {
            return "scripted";
        }

        @Override
        public ProviderAnswer authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException("this suite scripts only voids");
        }

        @Override
        public ProviderAnswer capture(CaptureRequest request) {
            throw new UnsupportedOperationException("this suite scripts only voids");
        }

        @Override
        public ProviderAnswer refund(RefundRequest request) {
            throw new UnsupportedOperationException("this suite scripts only voids");
        }

        @Override
        public ProviderAnswer voidAuthorization(VoidRequest request) {
            voidCalls++;
            transactionsCommittedAtCall = runner.committed;
            activeTransactionAtCall = runner.active;
            PaymentAttempt current = attempts.single();
            attemptStatusAtCall = current.status();
            referenceStoredAtCall =
                    current.voidReference() != null
                            && current.voidReference().value()
                                    .equals(request.reference().value());
            authorizationOnTheWire = request.authorization().value();
            return answer;
        }

        @Override
        public QueryAnswer query(ProviderIdempotencyReference ourReference) {
            throw new UnsupportedOperationException("this suite scripts only voids");
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
                            row.creditAccount(), CaptureMode.AUTOMATIC, row.amount(), to,
                            row.createdAt(), null));
            return true;
        }

        @Override
        public void recordTransition(
                Connection uow, PaymentIntentId id, PaymentIntentStatus from,
                PaymentIntentStatus to, Actor actor, Instant occurredAt) {}
    }

    private static final class FakeAttemptStore implements PaymentAttemptStore<Connection> {

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
        final Map<UUID, PaymentAttempt> rows = new HashMap<>();
        /** A sibling void wins the conditional between this instance's read and write. */
        boolean voidWinsNextVoidDispatch;
        /** The capture chain wins it instead: the promise is being taken, not released. */
        boolean captureWinsNextVoidDispatch;

        PaymentAttempt single() {
            assertThat(rows).hasSize(1);
            return rows.values().iterator().next();
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
        public boolean dispatchVoid(
                Connection uow, PaymentAttemptId id, PaymentAttemptStatus from,
                ProviderIdempotencyReference reference) {
            if (voidWinsNextVoidDispatch) {
                voidWinsNextVoidDispatch = false;
                // What the winner committed between this instance's read and write.
                rows.computeIfPresent(
                        id.value(),
                        (key, row) -> row.dispatchVoid(
                                new ProviderIdempotencyReference("void-winner")));
                return false;
            }
            if (captureWinsNextVoidDispatch) {
                captureWinsNextVoidDispatch = false;
                rows.computeIfPresent(
                        id.value(),
                        (key, row) -> row.dispatchCapture(
                                new ProviderIdempotencyReference("cap-winner")));
                return false;
            }
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
