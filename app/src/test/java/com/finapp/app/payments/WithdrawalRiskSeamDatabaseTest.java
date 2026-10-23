package com.finapp.app.payments;

import static com.finapp.app.payments.HeldWithdrawalFixtures.CLOCK;
import static com.finapp.app.payments.HeldWithdrawalFixtures.EUR;
import static com.finapp.app.payments.HeldWithdrawalFixtures.IDS;
import static com.finapp.app.payments.HeldWithdrawalFixtures.assertNoRiskWord;
import static com.finapp.app.payments.HeldWithdrawalFixtures.column;
import static com.finapp.app.payments.HeldWithdrawalFixtures.count;
import static com.finapp.app.payments.HeldWithdrawalFixtures.eur;
import static com.finapp.app.payments.HeldWithdrawalFixtures.field;
import static com.finapp.app.payments.HeldWithdrawalFixtures.holdStatus;
import static com.finapp.app.payments.HeldWithdrawalFixtures.someKey;
import static com.finapp.app.payments.HeldWithdrawalFixtures.windowCount;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.HeldWithdrawalFixtures.Customer;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.SignalAttributes;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Withdrawals judged by risk against a live PostgreSQL, through the composition root's own wiring (`P13-TSK-009`;
 * {@code PHASE_13_PLAN.md} sections 5, 7.2-7.4, 12.3, 14, scenarios 7, 9, 10 and 14; ADR-0062 section 6, ADR-0108;
 * {@code INV-BAL-04}, {@code INV-RSK-01}-{@code -04}, {@code INV-RSK-08}, {@code INV-CON-03}, {@code INV-IDEM-01}):
 * payments' real engine judged in-lock after its hold by risk's real adapter. A {@code HOLD} commits {@code HELD} with
 * one {@code ACTIVE} hold and nothing sent; a {@code DENY} commits {@code FAILED(RISK_REFUSED)} with the hold released in
 * the same transaction and nothing counted; one assessment per withdrawal and a retried key replaying without a second;
 * the door's {@code 403} writing nothing; ten withdrawals of one party counted exactly as their assessments; no lock
 * cycle against a concurrent transfer of the same party nor against a withdrawal from the party's other wallet; the
 * unavailable path's {@code DENY} at every band; a dispatch the scheme refuses leaving the decision standing.
 * <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task; the owner-authorised
 * database pass before the exit review runs it).
 *
 * <p><strong>Its own container</strong> ({@code own-container}): it activates the C8 EUR {@code WITHDRAWAL} version,
 * after which every withdrawal in the database is judged by it - a shared container's USD withdrawals would be refused
 * by the open-ended fail-safe band, and its EUR 2,500 ones held. The first case runs before the activation (the
 * production default: no version, {@code ALLOW(NO_POLICY_ACTIVE)} recorded); every other case activates once and keys its
 * subjects fresh.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("withdrawals judged by risk: the seam in the real engine, HELD and refused, the door's step-up"
        + " (P13-TSK-009)")
class WithdrawalRiskSeamDatabaseTest {

    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Withdrawals engine;
    @Autowired private TransferExecution transferExecution;

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

    // ------------------------------------------------------------------ before any version

    @Test
    @Order(1)
    @DisplayName("scenario 9, no policy: the production default - ALLOW(NO_POLICY_ACTIVE) recorded and counted, the"
            + " withdrawal dispatched and sent")
    void noPolicyRecordsAllowAndTheWithdrawalDispatches() throws Exception {
        assertThat(ACTIVATED).as("this case runs before the suite activates a version").isFalse();
        Customer customer = fixtures.funded(1_000_00);
        Withdrawals.Initiated initiated = withdraw(customer, 50_00, someKey());
        assertThat(initiated.status()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause || ','"
                + " || velocity_outcome FROM risk.risk_assessment WHERE operation = 'WITHDRAWAL' AND command_ref = ?",
                initiated.id().value().toString())).containsExactly("ALLOW,NO_POLICY_ACTIVE,NO_POLICY,COUNTED");
        assertThat(windowCount("PARTY", customer.party(), "D1")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ HOLD and DENY

    @Test
    @Order(2)
    @DisplayName("a HOLD commits HELD with the hold kept: one ACTIVE hold, held_until = held_at + 72 h on the"
            + " database clock, nothing sent, nothing counted; the customer reads HELD as a status word only")
    void aHoldCommitsHeldWithTheHoldKept() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(5_000_00);
        Withdrawals.Initiated held = withdraw(customer, 2_600_00, someKey());
        UUID id = held.id().value();
        assertThat(held.status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(held.failureReason()).isEmpty();
        assertThat(fixtures.sends()).as("a held withdrawal is never sent").isZero();
        assertThat(holdStatus(id)).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN payments.withdrawal w ON w.hold_reference = h.id"
                + " WHERE w.id = ? AND h.status = 'ACTIVE'", id)).isEqualTo(1);
        assertThat(column("SELECT (held_until - held_at)::text FROM payments.withdrawal WHERE id = ?", id))
                .containsExactly("72:00:00");
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE id = ? AND held_at >= created_at"
                + " AND last_dispatched_at <= held_at", id)).isEqualTo(1);
        assertThat(column("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment"
                + " WHERE operation = 'WITHDRAWAL' AND command_ref = ?", id.toString()))
                .containsExactly("HOLD,NOT_PERMITTED");
        assertThat(windowCount("PARTY", customer.party(), "D1")).as("a HOLD counts nothing").isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'payments.WithdrawalHeld'"
                + " AND aggregate_id = ?", id)).isEqualTo(1);

        HttpResponse<String> read = fixtures.get("/v1/me/withdrawals/" + id, customer.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("HELD");
        assertThat(read.body()).doesNotContain("failureReason\":\"");
        assertNoRiskWord(read.body());
    }

    @Test
    @Order(3)
    @DisplayName("a DENY commits FAILED(RISK_REFUSED) and releases the hold in the same transaction: no ACTIVE hold,"
            + " nothing sent, nothing counted - the C1 fail-safe's DENY, the party's lock held elsewhere past the budget")
    void aDenialReleasesTheHoldInTheSameTransaction() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(1_000_00);
        Withdrawals.Initiated refused;
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(131, "
                        + VelocityWindows.lockKey(VelocityDimension.PARTY, customer.party().toString()) + ")");
            }
            refused = withdraw(customer, 10_00, someKey());
            blocker.rollback();
        }
        UUID id = refused.id().value();
        assertThat(refused.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(refused.failureReason()).contains(com.finapp.payments.WithdrawalFailureReason.RISK_REFUSED);
        assertThat(holdStatus(id)).as("INV-BAL-04: released with the refusal").isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN ledger.ledger_account a ON a.id = h.ledger_account_id"
                + " WHERE a.owner_ref = ? AND h.status = 'ACTIVE'", customer.product())).isZero();
        assertThat(fixtures.sends()).isZero();
        assertThat(column("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause || ','"
                + " || velocity_outcome FROM risk.risk_assessment WHERE command_ref = ?", id.toString()))
                .containsExactly("DENY,RISK_UNAVAILABLE,TIMEOUT,NOT_PERMITTED");
        assertThat(windowCount("PARTY", customer.party(), "D1")).isZero();
        assertThat(count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?", id))
                .as("born FAILED: no edge was taken").isZero();
    }

    @Test
    @Order(4)
    @DisplayName("scenario 9, unavailable: DENY at every band for withdrawals (C1) - EUR 0.01 and EUR 4,000 alike,"
            + " recorded TIMEOUT, the dispatch transaction never aborted")
    void riskUnavailableCommitsDenyAtEveryBand() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(5_000_00);
        List<Withdrawals.Initiated> answers = new ArrayList<>();
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(131, "
                        + VelocityWindows.lockKey(VelocityDimension.PARTY, customer.party().toString()) + ")");
            }
            answers.add(withdraw(customer, 1, someKey()));
            answers.add(withdraw(customer, 4_000_00, someKey()));
            blocker.rollback();
        }
        assertThat(answers).allSatisfy(answer -> {
            assertThat(answer.status()).isEqualTo(WithdrawalStatus.FAILED);
            assertThat(column("SELECT decision || ',' || unavailable_cause FROM risk.risk_assessment"
                    + " WHERE command_ref = ?", answer.id().value().toString())).containsExactly("DENY,TIMEOUT");
        });
    }

    // ------------------------------------------------------------------ one assessment, the key

    @Test
    @Order(5)
    @DisplayName("one assessment per withdrawal, and a retry under the same key replays the judged status without a"
            + " second assessment, a second hold or a second count (INV-RSK-01, INV-IDEM-01)")
    void aRetryUnderTheSameKeyReplays() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(5_000_00);
        String key = someKey();
        Withdrawals.Initiated first = withdraw(customer, 2_600_00, key);
        Withdrawals.Initiated retry = withdraw(customer, 2_600_00, key);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE customer_id = ?", customer.customer()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment a JOIN platform.outbox_event e"
                + " ON e.aggregate_id = a.id WHERE a.party_id = ? AND e.event_type = 'risk.RiskDecisionMade'",
                customer.party())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the door

    @Test
    @Order(6)
    @DisplayName("the door's step-up: a recovered identity's withdrawal from a password session is 403"
            + " identity.AssuranceRequired with nothing written - no withdrawal, no hold, no assessment, no window, the"
            + " key never claimed")
    void theDoorsStepUpWritesNothing() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(1_000_00);
        String method = fixtures.registerBankAccount(customer);
        recovered(customer.identity());
        String key = someKey();
        HttpResponse<String> refused = fixtures.withdraw(customer.token(), method, "10.00", key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertNoRiskWord(refused.body());
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE customer_id = ?", customer.customer()))
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN ledger.ledger_account a ON a.id = h.ledger_account_id"
                + " WHERE a.owner_ref = ?", customer.product())).isZero();
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party())).isZero();
        assertThat(windowCount("PARTY", customer.party(), "D1")).isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE scope = ? AND idempotency_key = ?",
                Withdrawals.claimScopeOf(customer.customer()), key)).as("the key was never claimed").isZero();
    }

    // ------------------------------------------------------------------ concurrency and the lock order

    @Test
    @Order(7)
    @DisplayName("scenario 14 through the seam: ten concurrent withdrawals of one party - the window equal to the"
            + " counted assessments, ten unless a budget breach was recorded (R2a)")
    void tenWithdrawalsOfOneSubjectCountTen() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(1_000_00);
        List<Withdrawals.Initiated> answers = race(java.util.stream.IntStream.range(0, 10)
                .<Callable<Withdrawals.Initiated>>mapToObj(i -> () -> withdraw(customer, 1_00, someKey()))
                .toList());
        assertThat(answers).hasSize(10);
        long counted = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND velocity_outcome ="
                + " 'COUNTED'", customer.party());
        long breached = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND decision = 'DENY'"
                + " AND fail_safe_reason = 'RISK_UNAVAILABLE'", customer.party());
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party()))
                .isEqualTo(10);
        assertThat(counted + breached).as("every assessment counted, or its breach recorded").isEqualTo(10);
        assertThat(windowCount("PARTY", customer.party(), "D1")).as("the window is exactly its evidence (INV-RSK-03)")
                .isEqualTo(counted);
        assertThat(breached).as("ten counted when no budget was breached (the expected run)").isZero();
    }

    @Test
    @Order(8)
    @DisplayName("no lock cycle against a concurrent transfer of the same party: ten withdrawals and ten transfers"
            + " over one wallet at once - the wallet row first, then (R1)-(R3); every answer a domain outcome")
    void noLockCycleAgainstATransferOfTheSameParty() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(5_000_00);
        Customer payee = fixtures.customer();
        List<Callable<Object>> both = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            both.add(() -> withdraw(customer, 1_00, someKey()));
            both.add(() -> transfer(customer, payee.product(), eur(1_00)));
        }
        List<Object> answers = race(both);
        assertThat(answers).hasSize(20).allSatisfy(answer -> assertThat(answer).isNotNull());
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party()))
                .as("ten withdrawals and ten transfers, each judged once").isEqualTo(20);
    }

    @Test
    @Order(9)
    @DisplayName("no lock cycle against a withdrawal of the same party from its other wallet: EUR and GBP at once -"
            + " disjoint money rows, one PARTY lock in one global order; every answer a domain outcome")
    void noLockCycleAgainstAWithdrawalFromAnotherWallet() throws Exception {
        activateWithdrawalPolicy();
        Customer customer = fixtures.funded(1_000_00);
        CurrencyCode gbp = CurrencyCode.of("GBP");
        fixtures.addCurrency(customer, gbp);
        HeldWithdrawalFixtures.fund(customer.product(), gbp, 1_000_00);
        List<Callable<Withdrawals.Initiated>> both = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            both.add(() -> withdraw(customer, EUR, 1_00, someKey()));
            both.add(() -> withdraw(customer, gbp, 1_00, someKey()));
        }
        List<Withdrawals.Initiated> answers = race(both);
        assertThat(answers).hasSize(20);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party()))
                .isEqualTo(20);
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE customer_id = ?", customer.customer()))
                .isEqualTo(20);
    }

    // ------------------------------------------------------------------ scenario 7

    @Test
    @Order(10)
    @DisplayName("scenario 7, the withdrawal half: the scheme refuses after the permit - FAILED(DECLINED) by payments'"
            + " own path, the hold released; the decision stands, sealed ALLOW and COUNTED, and risk retries nothing")
    void aFailedDispatchLeavesTheDecisionStanding() throws Exception {
        activateWithdrawalPolicy();
        fixtures.schemeRejects();
        Customer customer = fixtures.funded(1_000_00);
        Withdrawals.Initiated declined = withdraw(customer, 20_00, someKey());
        assertThat(declined.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(declined.failureReason()).contains(com.finapp.payments.WithdrawalFailureReason.DECLINED);
        assertThat(column("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment WHERE command_ref = ?",
                declined.id().value().toString())).containsExactly("ALLOW,COUNTED");
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party()))
                .as("one judgement, never a second").isEqualTo(1);
        assertThat(holdStatus(declined.id().value())).isEqualTo("RELEASED");
    }

    // ------------------------------------------------------------------ the engine, directly

    private Withdrawals.Initiated withdraw(Customer customer, long minor, String key) throws Exception {
        return withdraw(customer, EUR, minor, key);
    }

    /** The composition root's own engine, as the customer, outside a request: no session, so a challenge refuses. */
    private Withdrawals.Initiated withdraw(Customer customer, CurrencyCode currency, long minor, String key)
            throws Exception {
        Withdrawals.Resolved resolved = HeldWithdrawalFixtures.resolved(customer, currency);
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(customer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return engine.withdraw(key, Money.ofMinorUnits(minor, currency), uow -> resolved);
        }
    }

    private Object transfer(Customer payer, UUID destination, Money amount) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(payer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            Object result = transferExecution.execute(app, new TransferCommand(someKey(), payer.party(),
                    payer.product(), destination, amount, "rent"));
            app.commit();
            return result;
        }
    }

    // ------------------------------------------------------------------ the policy and the signals

    private static void activateWithdrawalPolicy() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        RiskPolicyAdministration administration = new RiskPolicyAdministration(new JdbcRiskPolicyStore(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
        RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL), "the seam suite's withdrawal version", employee(),
                CorrelationId.generate(IDS)));
        inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated",
                CorrelationId.generate(IDS)));
    }

    /** A recovery completed ten hours ago - RECENT_RECOVERY's 300, the WITHDRAWAL version's CHALLENGE band. */
    private static void recovered(UUID identity) {
        inOneTransaction(uow -> new JdbcRiskSignalStore().insert(uow, new RiskSignal(RiskSignalId.next(IDS),
                SignalKind.RECOVERY_COMPLETED, SignalSubject.of(SubjectKind.IDENTITY, identity),
                new RiskSignal.SourceEvent(EventId.next(IDS), "identity.RecoveryCompleted", 1, "identity",
                        CorrelationId.of("flow"), CausationId.of("cause")),
                CLOCK.instant().minus(Duration.ofHours(10)),
                SignalAttributes.of(SignalKind.RECOVERY_COMPLETED,
                        Map.of("request_ref", IDS.next().toString(), "channel_ref", IDS.next().toString())))));
    }

    private static <R> R inOneTransaction(java.util.function.Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            connection.setAutoCommit(false);
            R result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
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
