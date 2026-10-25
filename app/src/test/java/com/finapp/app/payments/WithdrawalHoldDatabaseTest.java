package com.finapp.app.payments;

import static com.finapp.app.payments.HeldWithdrawalFixtures.EUR;
import static com.finapp.app.payments.HeldWithdrawalFixtures.IDS;
import static com.finapp.app.payments.HeldWithdrawalFixtures.column;
import static com.finapp.app.payments.HeldWithdrawalFixtures.count;
import static com.finapp.app.payments.HeldWithdrawalFixtures.holdStatus;
import static com.finapp.app.payments.HeldWithdrawalFixtures.someKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.HeldWithdrawalFixtures.Customer;
import com.finapp.ledger.HoldService;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.PushRail;
import com.finapp.payments.RailId;
import com.finapp.payments.RailOperations;
import com.finapp.payments.RoutingStore;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WithdrawalOutcomes;
import com.finapp.payments.WithdrawalResolution;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.WithdrawalStore;
import com.finapp.payments.WithdrawalVerdict;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.CanonicalEvaluation;
import com.finapp.risk.CommandRef;
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.DecisionOverrideStore;
import com.finapp.risk.DecisionOverrides;
import com.finapp.risk.EvaluationInputs;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskActionId;
import com.finapp.risk.RiskActions;
import com.finapp.risk.OverrideResolution;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEngineV1;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.Verdict;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The held withdrawal's doors and its expiry against a live PostgreSQL (`P13-TSK-009`; {@code PHASE_13_PLAN.md}
 * sections 5, 7.4, 14, scenario 10; ADR-0108; {@code INV-BAL-04}, {@code INV-RSK-02}, {@code INV-LIFE-02}): the REAL
 * payments engine, outcomes, resolution and ledger holds of the composition root, judged by a scripted seam that
 * answers {@code HOLD} - so no risk version is activated and the suite shares the container. Ten releases of one held
 * withdrawal dispatch it once; a release racing the expiry leaves exactly one outcome on the row lock - payments' door alone (the
 * expiry half, `-009`) and an approved override's issued release through risk's action machine ({@code RiskActions}
 * - the {@code risk_action} row under the override's id since `P13-TSK-017`, dispatched as the platform) and the
 * composition's {@code HeldWithdrawalActions} (the override half, `P13-TSK-012`: the override's recorded outcome is
 * {@code DONE} exactly when the release won, {@code ALREADY} or {@code REFUSED} when the expiry did), and an approved
 * override's release and denial each take effect once however often their one key is re-driven (scenario 10); the expiry
 * fails it
 * {@code RISK_REFUSED}/{@code HOLD_EXPIRED} and releases its hold
 * once however many sweepers race; a held wallet cannot be closed while its hold is {@code ACTIVE}. <strong>Written and
 * owed</strong> (C5).
 *
 * <p>Only this suite leaves a {@code HELD} row in the shared container ({@code WithdrawalRiskSeamDatabaseTest}, which
 * holds by policy, runs in its own), so the expiry leg - which claims every due row in the database - judges only
 * this suite's rows; every assertion is still made per withdrawal.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the held withdrawal's doors and expiry: exactly one outcome (P13-TSK-009)")
class WithdrawalHoldDatabaseTest {

    private static final Clock CLOCK = HeldWithdrawalFixtures.CLOCK;
    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private WithdrawalStore<Connection> withdrawalStore;
    @Autowired private WithdrawalOutcomes outcomes;
    @Autowired private WithdrawalResolution resolution;
    @Autowired private HoldService holdService;
    @Autowired private RoutingStore<Connection> routingStore;
    @Autowired private PaymentRails rails;
    @Autowired private PushRail instantRail;
    @Autowired private ProviderEvidenceStore<Connection> evidence;
    @Autowired private IdempotentExecutor executor;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private TransactionRunner transactions;
    @Autowired private HeldWithdrawalActions heldWithdrawalActions;
    @Autowired private HeldPaymentActions heldPaymentActions;
    @Autowired private RiskPolicyAdministration riskPolicyAdministration;
    @Autowired private RiskPolicies riskPolicies;
    @Autowired private RiskAssessmentStore riskAssessmentStore;
    @Autowired private DecisionOverrideStore decisionOverrideStore;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private RiskActions riskActions;

    private HeldWithdrawalFixtures fixtures;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
        fixtures = new HeldWithdrawalFixtures(port, provider);
        fixtures.schemeAccepts();
    }

    // ------------------------------------------------------------------ the release

    @Test
    @DisplayName("ten releases of one held withdrawal dispatch it once: one DONE, nine ALREADY, one HELD -> DISPATCHED"
            + " edge, one send")
    void tenReleasesOfOneHeldWithdrawalDispatchOnce() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        UUID held = held(customer, 100_00, Duration.ofHours(72));
        List<Withdrawals.Held> answers = race(java.util.stream.IntStream.range(0, 10)
                .<Callable<Withdrawals.Held>>mapToObj(i -> () -> asOperator(engine -> engine.releaseHeld(
                        "risk.action:" + UUID.randomUUID(), com.finapp.payments.WithdrawalId.of(held),
                        "OVERRIDE_APPROVED")))
                .toList());
        assertThat(answers).filteredOn(a -> a.outcome() == Withdrawals.HeldOutcome.DONE).hasSize(1);
        assertThat(answers).filteredOn(a -> a.outcome() == Withdrawals.HeldOutcome.ALREADY).hasSize(9);
        assertThat(count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?"
                + " AND from_status = 'HELD' AND to_status = 'DISPATCHED'", held)).isEqualTo(1);
        assertThat(fixtures.sends()).as("one send of our reference").isEqualTo(1);
        assertThat(column("SELECT status FROM payments.withdrawal WHERE id = ?", held)).containsExactly("COMPLETED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.WithdrawalReleased'"
                + " AND target_id = ?", held.toString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ release versus expiry

    @Test
    @DisplayName("a release racing the expiry at held_until leaves exactly one outcome on the withdrawal row's lock:"
            + " released and never expired, or expired - FAILED(RISK_REFUSED, HOLD_EXPIRED), the hold released - and"
            + " the release ALREADY, or REFUSED past the validity, with nothing sent")
    void releaseAndExpiryLeaveExactlyOne() throws Exception {
        Customer customer = fixtures.funded(5_000_00);
        for (int round = 0; round < 5; round++) {
            UUID held = held(customer, 10_00, Duration.ofSeconds(1));
            // Race at the boundary: either side may find the row within or past held_until.
            Thread.sleep(950 + round * 20L);
            int sendsBefore = fixtures.sends();
            List<Object> answers = race(List.<Callable<Object>>of(
                    () -> asOperator(engine -> engine.releaseHeld("risk.action:" + UUID.randomUUID(),
                            com.finapp.payments.WithdrawalId.of(held), "OVERRIDE_APPROVED")),
                    () -> asPlatform(() -> resolution.expireHolds())));
            Withdrawals.Held release = (Withdrawals.Held) answers.get(0);
            // A release refused as late, before the racing sweep could see the lapse, leaves the row to the next
            // tick: the leg runs once more, past the validity, and then exactly one outcome stands.
            Thread.sleep(200);
            asPlatform(() -> resolution.expireHolds());
            long exits = count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?"
                    + " AND from_status = 'HELD'", held);
            assertThat(exits).as("round %d: exactly one edge out of HELD", round).isEqualTo(1);
            if (release.outcome() == Withdrawals.HeldOutcome.DONE) {
                assertThat(column("SELECT failure_detail FROM payments.withdrawal WHERE id = ?", held))
                        .containsExactly((String) null);
                assertThat(fixtures.sends()).isEqualTo(sendsBefore + 1);
            } else {
                assertThat(release.outcome()).isIn(Withdrawals.HeldOutcome.ALREADY, Withdrawals.HeldOutcome.REFUSED);
                assertThat(column("SELECT status || ',' || failure_reason || ',' || failure_detail"
                        + " FROM payments.withdrawal WHERE id = ?", held))
                        .containsExactly("FAILED,RISK_REFUSED,HOLD_EXPIRED");
                assertThat(holdStatus(held)).isEqualTo("RELEASED");
                assertThat(fixtures.sends()).isEqualTo(sendsBefore);
            }

            // The override half (P13-TSK-012, folded by P13-TSK-017): an approved override's issued release - the action
            // machine's row under the override's id, dispatched as the platform through the composition's
            // HeldWithdrawalActions, keyed risk.action:<override id> - racing the expiry.
            UUID overridden = held(customer, 10_00, Duration.ofSeconds(1));
            RiskAction issued = approvedOverrideOf(overridden, OverrideResolution.PROCEED);
            Thread.sleep(950 + round * 20L);
            int overrideSendsBefore = fixtures.sends();
            List<Object> raced = race(List.<Callable<Object>>of(
                    () -> asSystem(() -> {
                        RiskAction claimed = claimed(issued);
                        ActionOutcome outcome = riskActions.dispatch(claimed);
                        transactionsDo(uow -> riskActions.conclude(uow, claimed, outcome,
                                CorrelationId.of(claimed.correlationId())));
                        return outcome;
                    }),
                    () -> asPlatform(() -> resolution.expireHolds())));
            ActionOutcome overrideOutcome = (ActionOutcome) raced.get(0);
            Thread.sleep(200);
            asPlatform(() -> resolution.expireHolds());
            assertThat(count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?"
                    + " AND from_status = 'HELD'", overridden)).as("round %d, the override: one edge out of HELD", round)
                    .isEqualTo(1);
            assertThat(column(ACTION_OUTCOME, overridden))
                    .as("the override's action records payments' answer once").containsExactly(overrideOutcome.name());
            if (overrideOutcome == ActionOutcome.DONE) {
                assertThat(fixtures.sends()).isEqualTo(overrideSendsBefore + 1);
                assertThat(column("SELECT failure_detail FROM payments.withdrawal WHERE id = ?", overridden))
                        .containsExactly((String) null);
            } else {
                assertThat(overrideOutcome).isIn(ActionOutcome.ALREADY, ActionOutcome.REFUSED);
                assertThat(column("SELECT status || ',' || failure_reason || ',' || failure_detail"
                        + " FROM payments.withdrawal WHERE id = ?", overridden))
                        .containsExactly("FAILED,RISK_REFUSED,HOLD_EXPIRED");
                assertThat(holdStatus(overridden)).isEqualTo("RELEASED");
                assertThat(fixtures.sends()).isEqualTo(overrideSendsBefore);
            }
        }
    }

    // ------------------------------------------------------------------ the override's issued action (P13-TSK-012)

    @Test
    @DisplayName("scenario 10, the release: ten re-drives of one approved override's issued release under its one key"
            + " risk.action:<override id> - one HELD -> DISPATCHED edge, one send, every answer DONE (the key's replay),"
            + " the outcome recorded once")
    void anOverridesReleaseDispatchesOnce() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        UUID held = held(customer, 50_00, Duration.ofHours(72));
        RiskAction claimed = asSystem(() -> claimed(approvedOverrideOf(held,
                com.finapp.risk.OverrideResolution.PROCEED)));
        List<ActionOutcome> answers = race(java.util.stream.IntStream.range(0, 10)
                .<Callable<ActionOutcome>>mapToObj(i -> () -> asSystem(() -> {
                    try {
                        ActionOutcome outcome = riskActions.dispatch(claimed);
                        transactionsDo(uow -> riskActions.conclude(uow, claimed, outcome,
                                CorrelationId.of(claimed.correlationId())));
                        return outcome;
                    } catch (RuntimeException inFlight) {
                        return null; // another flight held the key - nothing recorded, re-driven later
                    }
                }))
                .toList());
        assertThat(answers).filteredOn(java.util.Objects::nonNull).isNotEmpty().containsOnly(ActionOutcome.DONE);
        assertThat(count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?"
                + " AND from_status = 'HELD' AND to_status = 'DISPATCHED'", held)).isEqualTo(1);
        assertThat(fixtures.sends()).as("one send of our reference").isEqualTo(1);
        assertThat(column(ACTION_OUTCOME, held)).containsExactly("DONE");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ActionConcluded'"
                + " AND target_id = (SELECT id::text FROM risk.decision_override WHERE target_id = ?)", held))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 10, the denial: an approved override's REFUSE denies the held withdrawal through payments'"
            + " door - FAILED(RISK_REFUSED), the hold released once, nothing sent - and a re-drive of the same key"
            + " replays DONE, releasing nothing twice")
    void anOverridesDenialReleasesTheHoldOnce() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        UUID held = held(customer, 40_00, Duration.ofHours(72));
        RiskAction claimed = asSystem(() -> claimed(approvedOverrideOf(held,
                com.finapp.risk.OverrideResolution.REFUSE)));
        int sendsBefore = fixtures.sends();
        ActionOutcome first = asSystem(() -> riskActions.dispatch(claimed));
        ActionOutcome again = asSystem(() -> riskActions.dispatch(claimed));
        assertThat(first).isEqualTo(ActionOutcome.DONE);
        assertThat(again).as("the key's answer replayed").isEqualTo(ActionOutcome.DONE);
        assertThat(column("SELECT status || ',' || failure_reason FROM payments.withdrawal WHERE id = ?", held))
                .containsExactly("FAILED,RISK_REFUSED");
        assertThat(holdStatus(held)).isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'ledger.HoldReleased'"
                + " AND target_id = (SELECT hold_reference::text FROM payments.withdrawal WHERE id = ?)", held))
                .isEqualTo(1);
        assertThat(fixtures.sends()).isEqualTo(sendsBefore);
    }

    // ------------------------------------------------------------------ the expiry

    @Test
    @DisplayName("the expiry releases the hold once: ten sweepers over one due row - one FAILED(RISK_REFUSED,"
            + " HOLD_EXPIRED) edge, one hold release, one payments.WithdrawalHoldExpired, as the platform")
    void theExpiryReleasesTheHoldOnce() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        UUID held = held(customer, 25_00, Duration.ofMillis(200));
        Thread.sleep(400);
        race(java.util.stream.IntStream.range(0, 10)
                .<Callable<WithdrawalResolution.ExpiryResult>>mapToObj(i -> () -> asPlatform(resolution::expireHolds))
                .toList());
        assertThat(column("SELECT status || ',' || failure_reason || ',' || failure_detail"
                + " FROM payments.withdrawal WHERE id = ?", held)).containsExactly("FAILED,RISK_REFUSED,HOLD_EXPIRED");
        assertThat(count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?"
                + " AND from_status = 'HELD' AND to_status = 'FAILED' AND actor_type = 'SYSTEM'", held)).isEqualTo(1);
        assertThat(holdStatus(held)).isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.WithdrawalHoldExpired'"
                + " AND target_id = ?", held.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'ledger.HoldReleased'"
                + " AND target_id = (SELECT hold_reference::text FROM payments.withdrawal WHERE id = ?)", held))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'payments.WithdrawalFailed'"
                + " AND aggregate_id = ?", held)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the wallet

    @Test
    @DisplayName("a held wallet cannot close: the settled balance drained to zero beside the standing hold, the close is"
            + " 409 accounts.AccountNotEmpty; denied - the hold released - it closes")
    void aHeldWalletCannotClose() throws Exception {
        Customer customer = fixtures.funded(30_00);
        UUID held = held(customer, 30_00, Duration.ofHours(72));
        // Postings are not gated by holds: settled reaches zero while the reservation stands (AccountClosing's case).
        HeldWithdrawalFixtures.fund(customer.product(), EUR, -30_00);
        HttpResponse<String> refused = fixtures.delete("/v1/me/accounts/" + customer.product(), customer.token());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("accounts.AccountNotEmpty");
        assertThat(holdStatus(held)).isEqualTo("ACTIVE");

        Withdrawals.Held denied = asOperator(engine -> engine.denyHeld("risk.action:" + UUID.randomUUID(),
                com.finapp.payments.WithdrawalId.of(held), "OVERRIDE_REJECTED"));
        assertThat(denied.outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        assertThat(holdStatus(held)).isEqualTo("RELEASED");
        HttpResponse<String> closed = fixtures.delete("/v1/me/accounts/" + customer.product(), customer.token());
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ the override half's fixtures (P13-TSK-012)

    /**
     * An approved override of a HOLD sealed for {@code withdrawal} - under a WITHDRAWAL version PROPOSED for it (never
     * activated, so nothing else in the shared container is judged by it) whose one band holds, below the C7 threshold so
     * one officer completes it - and its issued action (the action machine's row under the override's id, APPROVED and
     * due), awaiting its claim.
     */
    private RiskAction approvedOverrideOf(UUID withdrawal, OverrideResolution resolution) {
        RiskPolicy v1 = RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL);
        RiskPolicy holding = new RiskPolicy(RiskOperation.WITHDRAWAL, v1.engine(), v1.currency(), v1.rules(),
                List.of(new ScoreBand(0, 1000, RiskDecision.HOLD)), v1.ratingBands(), v1.failSafeBands(),
                v1.challengeAssurance(), v1.overrideThreshold(), v1.holdValidity());
        Actor officer = operator();
        RiskPolicyAdministration.Proposed version = transactionsDo(uow -> riskPolicyAdministration.propose(uow, holding,
                "the override half's version", officer, CorrelationId.generate(IDS)));
        Money amount = Money.ofMinorUnits(10_00, EUR);
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(RiskOperation.WITHDRAWAL, CommandRef.of(withdrawal),
                amount, UUID.randomUUID(), CorrelationId.generate(IDS), CausationId.of("hold-suite"))
                .sourceAccount(IDS.next()).build();
        EvaluationInputs inputs = request.judged().build();
        Verdict verdict = RiskEngineV1.evaluate(holding, inputs);
        String inputsText = CanonicalEvaluation.render(inputs);
        RiskAssessment assessment = new RiskAssessment(RiskAssessmentId.next(IDS), RiskOperation.WITHDRAWAL,
                request.commandRef(), RiskAssessment.Subjects.of(request), amount,
                Optional.of(new RiskAssessment.Pin(version.id(), version.version())), verdict.engine(), verdict.score(),
                RiskDecision.HOLD, Optional.empty(), Optional.empty(), VelocityOutcome.NOT_PERMITTED, Optional.empty(),
                inputsText, CanonicalEvaluation.sha256(inputsText),
                CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict)), verdict.hits(), verdict.reasons(), 100,
                request.correlation().value(), "hold-suite", Optional.empty());
        transactionsDo(uow -> riskAssessmentStore.insert(uow, assessment));
        DecisionOverrides machine = new DecisionOverrides(decisionOverrideStore, riskAssessmentStore, riskPolicies,
                (unitOfWork, actor) -> Optional.of(UUID.randomUUID()), auditWriter, riskActions, IDS, CLOCK);
        DecisionOverride proposed = transactionsDo(uow -> machine.propose(uow, assessment.id(), resolution,
                resolution == OverrideResolution.PROCEED ? "FALSE_POSITIVE" : "RISK_CONFIRMED",
                "the hold suite's override", officer, CorrelationId.generate(IDS)));
        transactionsDo(uow -> machine.approve(uow, assessment.id(), proposed.id(), "below the threshold, one holder",
                officer, CorrelationId.generate(IDS)));
        return transactionsDo(uow -> riskActions.current(uow, RiskActionId.ofOverride(proposed.id()))).orElseThrow();
    }

    /** {@code issued} claimed in a committed transaction of its own - T-act's first step, as the platform. */
    private RiskAction claimed(RiskAction issued) {
        return transactionsDo(uow -> riskActions.claim(uow, issued.id(), Duration.ofMinutes(2))).orElseThrow(() ->
                new IllegalStateException("an approved override's action is due at once"));
    }

    /** The outcome an override's action recorded - the action machine's row under the override's id. */
    private static final String ACTION_OUTCOME = "SELECT a.outcome FROM risk.risk_action a"
            + " JOIN risk.decision_override o ON o.id = a.override_id WHERE o.target_id = ?";

    private static Actor operator() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    /** {@code work} in one committed transaction on the application role's connection. */
    private static <T> T transactionsDo(java.util.function.Function<Connection, T> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <T> T asOperatorDo(java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(operator());
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ the engine, held by a scripted seam

    /** A withdrawal born HELD for {@code validity} - the composition's engine, its seam answering HOLD. */
    private UUID held(Customer customer, long minor, Duration validity) throws Exception {
        Withdrawals.Resolved resolved = HeldWithdrawalFixtures.resolved(customer, EUR);
        Withdrawals holding = engine(WithdrawalVerdict.hold(validity));
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(customer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            Withdrawals.Initiated initiated =
                    holding.withdraw(someKey(), Money.ofMinorUnits(minor, EUR), uow -> resolved);
            assertThat(initiated.status()).isEqualTo(WithdrawalStatus.HELD);
            return initiated.id().value();
        }
    }

    /** The composition's engine over its own beans, its risk seam answering {@code verdict}. */
    private Withdrawals engine(WithdrawalVerdict verdict) {
        return new Withdrawals(withdrawalStore, outcomes, holdService, routingStore, rails,
                RailOperations.ofPush(RailId.of("instant"), instantRail), evidence, executor, auditWriter, IDS, CLOCK,
                transactions, (unitOfWork, judged) -> verdict);
    }

    private interface Door {
        Withdrawals.Held open(Withdrawals engine);
    }

    private Withdrawals.Held asOperator(Door door) {
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return door.open(engine(WithdrawalVerdict.permit()));
        }
    }

    /**
     * {@code work} as T-act runs it (`P13-TSK-017`): the platform the acting principal - a person decided the action, the
     * platform executes it - inside a correlation.
     */
    @SuppressWarnings("try") // The Scopes are used for their close side effect (the established idiom).
    private static <T> T asSystem(java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private static <T> T asPlatform(java.util.function.Supplier<T> leg) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return leg.get();
        }
    }

    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get(90, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
