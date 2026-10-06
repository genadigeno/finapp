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
import java.util.LinkedHashMap;
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
    private final FakeRoutingStore routing = new FakeRoutingStore();

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
                routing,
                PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                (uow, record) -> auditTrail.add(record),
                (uow, envelope, payload, mediaType) -> events.add(envelope),
                IDS,
                CLOCK,
                RoutingTelemetry.NONE,
                com.finapp.payments.RailOperations.none());
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
                // The wallet top-up composition, which is what this suite's intents are: the
                // two lines PaymentOutcomes used to write inline (P6-TSK-005).
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
                new RecordingSettlementExpectations());
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
    @DisplayName("the winner pins its decision: chosen rail, pinned version, RailSelected -"
            + " one Tx1 with the attempt (P7-TSK-003, INV-RAIL-02, INV-HIST-04)")
    void theWinnerPinsItsDecision() {
        provider.answer = ProviderAnswer.approved(
                new ProviderReference("psp-auth-routed"), "body".getBytes());
        confirmation().confirm(party, intent.id());

        RoutingDecision decision = routing.latestFor(intent.id());
        assertThat(decision.chosenRail()).contains(SimulatedCardPspAdapter.RAIL.id());
        assertThat(decision.policyVersionId()).isEqualTo(routing.version.id());
        assertThat(decision.matchedRuleIndex()).contains(0);
        assertThat(decision.steps()).hasSize(1);
        assertThat(decision.steps().get(0).verdict()).isEqualTo(RoutingStepVerdict.CHOSEN);
        assertThat(decision.steps().get(0).descriptorVersion())
                .contains(SimulatedCardPspAdapter.RAIL.declarationVersion());
        assertThat(attempts.single().rail()).isEqualTo(SimulatedCardPspAdapter.RAIL.id());
        assertThat(events).anyMatch(e -> e.eventType().equals("payments.RailSelected"));
        assertThat(auditTrail)
                .as("the confirmation names the pin it dispatched under")
                .anyMatch(record ->
                        record.changeSummary()
                                .map(summary -> summary.contains("policyVersion=1")
                                        && summary.contains("decision="))
                                .orElse(false));
    }

    @Test
    @DisplayName("no eligible rail: refused AND recorded, the intent untouched and retryable"
            + " after the operator acts (P7-TSK-003, ADR-0060 section 3)")
    void noEligibleRailIsRecordedAndRetryable() {
        routing.availability.put(
                SimulatedCardPspAdapter.RAIL.id(),
                new RailAvailability(
                        SimulatedCardPspAdapter.RAIL.id(), false, "incident", "op-1",
                        Instant.now(CLOCK)));
        assertThatThrownBy(() -> confirmation().confirm(party, intent.id()))
                .isInstanceOf(NoEligibleRailException.class);

        // Refused AND recorded: the decision row is the explanation, the intent untouched,
        // the provider never asked.
        RoutingDecision refused = routing.latestFor(intent.id());
        assertThat(refused.chosenRail()).isEmpty();
        assertThat(refused.steps()).hasSize(1);
        assertThat(refused.steps().get(0).rejection())
                .contains(RoutingRejection.UNAVAILABLE);
        assertThat(refused.steps().get(0).railAvailable()).isFalse();
        assertThat(intents.rows.get(intent.id().value()).status())
                .isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        assertThat(provider.calls).isZero();

        // The operator re-enables the rail; the SAME confirm now succeeds and pins its
        // chosen decision - which is why the refusal must not consume the intent.
        routing.availability.clear();
        provider.answer = ProviderAnswer.approved(
                new ProviderReference("psp-auth-after"), "body".getBytes());
        confirmation().confirm(party, intent.id());
        assertThat(routing.latestFor(intent.id()).chosenRail())
                .contains(SimulatedCardPspAdapter.RAIL.id());
    }

    @Test
    @DisplayName("NOTHING_SENT appends the abandonment - knowledge, in the outcome's own"
            + " transaction (INV-RAIL-02)")
    void nothingSentAppendsTheAbandonment() {
        provider.answer = ProviderAnswer.nothingSent();
        confirmation().confirm(party, intent.id());

        RoutingDecision decision = routing.latestFor(intent.id());
        assertThat(decision.steps()).hasSize(2);
        assertThat(decision.steps().get(1).verdict()).isEqualTo(RoutingStepVerdict.ABANDONED);
        assertThat(decision.steps().get(1).rejection())
                .contains(RoutingRejection.NOTHING_SENT);
        assertThat(decision.steps().get(1).rail())
                .isEqualTo(SimulatedCardPspAdapter.RAIL.id());
    }

    @Test
    @DisplayName("INDETERMINATE appends nothing: ambiguity is not knowledge, and the attempt"
            + " stays on its rail (INV-RAIL-02, INV-LIFE-03)")
    void indeterminateAppendsNothing() {
        provider.answer = ProviderAnswer.indeterminate("garbage".getBytes());
        confirmation().confirm(party, intent.id());

        RoutingDecision decision = routing.latestFor(intent.id());
        assertThat(decision.steps())
                .as("the trail must not claim knowledge a refused connection never gave")
                .hasSize(1);
        assertThat(decision.steps().get(0).verdict()).isEqualTo(RoutingStepVerdict.CHOSEN);
        assertThat(attempts.single().status()).isEqualTo(PaymentAttemptStatus.AUTH_UNKNOWN);
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
                        routing,
                        PaymentRails.of(java.util.List.of(SimulatedCardPspAdapter.RAIL)),
                        (uow, record) -> auditTrail.add(record),
                        (uow, envelope, payload, mediaType) -> events.add(envelope),
                        IDS, CLOCK,
                        RoutingTelemetry.NONE,
                com.finapp.payments.RailOperations.none());
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
                        SimulatedCardPspAdapter.RAIL.id(),
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
        public Optional<Wallet> walletOwnedBy(
                Connection uow, UUID callerPartyId, CurrencyCode currency) {
            throw new UnsupportedOperationException("confirm never resolves the wallet");
        }

        @Override
        public Optional<Wallet> payerWalletOwnedBy(
                Connection uow, UUID callerPartyId, CurrencyCode currency) {
            // These suites confirm method-instrument intents; the book branch never asks.
            throw new UnsupportedOperationException(
                    "this suite's confirms never resolve the payer wallet");
        }

        @Override
        public Optional<ProviderReference> bankDestinationOwnedBy(
                Connection unitOfWork, UUID callerPartyId, UUID paymentMethodId) {
            return Optional.empty();
        }

        @Override
        public boolean creditable(Connection uow, LedgerAccountId account) {
            return true;
        }

        @Override
        public Optional<InstrumentToken> instrumentOwnedBy(
                Connection uow, UUID callerPartyId, UUID paymentMethodId) {
            return instrumentPresent
                    ? Optional.of(InstrumentToken.of("tok_test-4242"))
                    : Optional.empty();
        }

        /** The fake's one instrument is the card (P7-TSK-009's kind read). */
        @Override
        public Optional<InstrumentKind> instrumentKindOwnedBy(
                Connection uow, UUID callerPartyId, UUID paymentMethodId) {
            return instrumentOwnedBy(uow, callerPartyId, paymentMethodId)
                    .map(token -> InstrumentKind.CARD_TOKEN);
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
        public ProviderAnswer voidAuthorization(VoidRequest request) {
            throw new UnsupportedOperationException("this suite scripts no voids");
        }

        @Override
        public QueryAnswer query(ProviderIdempotencyReference ourReference) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * An in-memory routing store (`P7-TSK-003`): version 1 routes the card pay-in, exactly
     * as V013 seeds it; decisions and appended steps are recorded for the assertions.
     */
    private static final class FakeRoutingStore implements RoutingStore<Connection> {

        final RoutingPolicyVersion version =
                RoutingPolicyVersion.rehydrate(
                        RoutingPolicyVersionId.next(IDS),
                        1,
                        List.of(
                                new RoutingRule(
                                        RoutingRuleId.next(IDS),
                                        0,
                                        PaymentDirection.PAY_IN,
                                        InstrumentKind.CARD_TOKEN,
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(SimulatedCardPspAdapter.RAIL.id()))),
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "test",
                        "the seeded card route, in memory");

        final Map<RailId, RailAvailability> availability = new HashMap<>();
        final Map<UUID, RoutingDecision> decisions = new LinkedHashMap<>();

        RoutingDecision latestFor(PaymentIntentId intent) {
            return findLatestDecisionForIntent(null, intent).orElseThrow();
        }

        @Override
        public int nextVersionNumber(Connection uow) {
            return 2;
        }

        @Override
        public boolean insertVersionIfNumberIsFree(Connection uow, RoutingPolicyVersion v) {
            throw new UnsupportedOperationException("this suite mints no versions");
        }

        @Override
        public Optional<RoutingPolicyVersion> findVersionInForce(
                Connection uow, Instant at) {
            return Optional.of(version);
        }

        @Override
        public Optional<RoutingPolicyVersion> findVersionById(
                Connection uow, RoutingPolicyVersionId id) {
            return Optional.of(version).filter(v -> v.id().equals(id));
        }

        @Override
        public void recordAvailability(Connection uow, RailAvailability fact) {
            availability.put(fact.rail(), fact);
        }

        @Override
        public Map<RailId, RailAvailability> availabilityByRail(Connection uow) {
            return new HashMap<>(availability);
        }

        @Override
        public void insertDecision(Connection uow, RoutingDecision decision) {
            decisions.put(decision.id().value(), decision);
        }

        @Override
        public Optional<RoutingDecision> findLatestDecisionForOutboundCredit(
                Connection unitOfWork, OutboundCreditId credit) {
            return decisions.values().stream()
                    .filter(d -> d.subject().outboundCredit().map(credit::equals).orElse(false))
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<RoutingDecision> findLatestDecisionForWithdrawal(
                Connection unitOfWork, WithdrawalId withdrawal) {
            return decisions.values().stream()
                    .filter(d -> d.subject().withdrawal()
                            .map(withdrawal::equals)
                            .orElse(false))
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<RoutingDecision> findLatestDecisionForIntent(
                Connection uow, PaymentIntentId intent) {
            RoutingDecision latest = null;
            for (RoutingDecision decision : decisions.values()) {
                if (decision.intentId().equals(intent)) {
                    latest = decision;
                }
            }
            return Optional.ofNullable(latest);
        }

        @Override
        public void appendStep(Connection uow, RoutingDecisionId id, RoutingStep step) {
            RoutingDecision current = decisions.get(id.value());
            List<RoutingStep> extended = new ArrayList<>(current.steps());
            extended.add(step);
            decisions.put(
                    id.value(),
                    RoutingDecision.rehydrate(
                            current.id(),
                            current.subject(),
                            current.policyVersionId(),
                            current.direction(),
                            current.instrumentKind(),
                            current.amount(),
                            current.matchedRuleIndex(),
                            current.chosenRail(),
                            extended,
                            current.createdAt()));
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
                            row.creditAccount(), CaptureMode.AUTOMATIC, row.amount(), to, row.createdAt(), null);
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
        public java.util.Optional<PaymentAttempt> findByCaptureProviderReference(
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
        public void appendForOutboundCredit(
                Connection uow, OutboundCreditId credit, EvidenceKind kind, byte[] payload, Instant recordedAt) {
            payloads.add(payload.clone());
        }

        @Override
        public List<byte[]> payloadsFor(Connection uow, PaymentAttemptId attempt) {
            return List.copyOf(payloads);
        }

        // P8-TSK-014's metadata reads: this fake retains no rows to describe.
        @Override
        public List<EvidenceMetadata> evidenceMetadataFor(
                Connection uow, PaymentAttemptId attempt) {
            return List.of();
        }

        @Override
        public List<EvidenceMetadata> evidenceMetadataFor(Connection uow, RefundId refund) {
            return List.of();
        }

        @Override
        public List<EvidenceMetadata> evidenceMetadataFor(
                Connection uow, WithdrawalId withdrawal) {
            return List.of();
        }

        @Override
        public List<EvidenceMetadata> evidenceMetadataForUnmatched(
                Connection uow, java.util.UUID unmatchedConfirmation) {
            return List.of();
        }

        @Override
        public boolean evidenceExists(Connection uow, java.util.UUID evidenceId) {
            return false;
        }
    }
}
