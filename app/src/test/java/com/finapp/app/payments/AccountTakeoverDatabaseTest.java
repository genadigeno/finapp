package com.finapp.app.payments;

import static com.finapp.app.payments.HeldWithdrawalFixtures.CLOCK;
import static com.finapp.app.payments.HeldWithdrawalFixtures.EUR;
import static com.finapp.app.payments.HeldWithdrawalFixtures.IDS;
import static com.finapp.app.payments.HeldWithdrawalFixtures.column;
import static com.finapp.app.payments.HeldWithdrawalFixtures.count;
import static com.finapp.app.payments.HeldWithdrawalFixtures.someKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.HeldWithdrawalFixtures.Customer;
import com.finapp.app.risk.RiskSignalIntake;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.AccountTakeover;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.JdbcRiskAlertStore;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.NewAlert;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.WindowKind;
import com.finapp.risk.WindowStarts;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Account-takeover detection against a live PostgreSQL, through the composition root's own wiring (`P13-TSK-013`;
 * {@code PHASE_13_PLAN.md} sections 7.1, 12.6, scenario 11; ADR-0108, ADR-0110; {@code INV-RSK-06}, {@code INV-RSK-04},
 * {@code INV-RSK-08}): identity's events through risk's real inbox handlers on two instances, delayed, duplicated and
 * reordered; payments' real withdrawal engine judged in-lock by risk's real adapter; the profile leg's real recompute.
 * The sequence is held and alerted exactly once - one {@code ACCOUNT_TAKEOVER} alert, attached to the party's ONE
 * {@code ATO} case since `P13-TSK-016` (the alert's folding and the hold's {@code CaseOpener} meet on it), whatever the
 * retries, the redeliveries, the ten concurrent withdrawals and the sweeps; outside the lookback nothing is held; a
 * recovery with no movement is alerted once by the sweep; a raise blocked past the budget is undone behind its
 * savepoint and the withdrawal stands. <strong>Written and owed</strong> (owner decision C5: the database tiers are
 * skipped per task; the owner-authorised database pass before the exit review runs it). It lives beside
 * {@code WithdrawalRiskSeamDatabaseTest} to drive the real withdrawal door through {@link HeldWithdrawalFixtures}.
 *
 * <p><strong>Its own container</strong> ({@code own-container}): it activates the C8 EUR {@code WITHDRAWAL} and
 * {@code MONITORING} versions, after which every withdrawal and every profile in the database is judged by them.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("account-takeover detection on PostgreSQL: held and alerted once (P13-TSK-013)")
class AccountTakeoverDatabaseTest {

    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static final Duration RETENTION = Duration.ofDays(14);
    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Withdrawals engine;
    @Autowired private ProfileRecompute profileRecompute;
    @Autowired @Qualifier("riskSignalCredentialChanged") private RiskSignalIntake credentialChanged;
    @Autowired @Qualifier("riskSignalRecoveryCompleted") private RiskSignalIntake recoveryCompleted;

    private final InboxConsumer<Connection> inbox = new InboxConsumer<>(new JdbcInboxRecordStore(), CLOCK, RETENTION);
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
        activate();
    }

    // ------------------------------------------------------------------ scenario 11

    @Test
    @Order(1)
    @DisplayName("scenario 11: a credential change - delivered on two instances at once, again later, reordered behind"
            + " another event - then a new payee, then a withdrawal: HELD with its hold kept, and ONE ACCOUNT_TAKEOVER"
            + " alert on the party, ATTACHED to its one ATO case (P13-TSK-016); the retry replays, a second withdrawal"
            + " holds again - still one alert, one case")
    void aCredentialChangeThenNewPayeeThenWithdrawalIsHeldAndCased() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        Instant changedAt = Instant.now(CLOCK).minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        ReceivedEvent changed = credentialChangedEvent(customer.identity(), changedAt);
        ReceivedEvent earlier = credentialChangedEvent(customer.identity(), changedAt.minus(Duration.ofHours(5)));
        List<InboxConsumer.Outcome> outcomes = race(List.of(
                () -> deliver(credentialChanged, changed), () -> deliver(credentialChanged, changed)));
        assertThat(outcomes).containsOnlyOnce(InboxConsumer.Outcome.PROCESSED);
        deliver(credentialChanged, earlier); // delayed: older than the one already recorded
        deliver(credentialChanged, changed); // and the first again, later
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE subject_ref = ? AND kind = 'CREDENTIAL_CHANGED'",
                customer.identity().toString())).as("one signal per event").isEqualTo(2);

        fixtures.registerBankAccount(customer); // the new payee, as the customer's door records it
        String key = someKey();
        Withdrawals.Initiated held = withdraw(customer, 10_00, key);
        assertThat(held.status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(fixtures.sends()).as("a held withdrawal is never sent").isZero();
        assertThat(HeldWithdrawalFixtures.holdStatus(held.id().value())).isEqualTo("ACTIVE");
        UUID assessment = assessmentOf(held);
        assertThat(column("SELECT decision FROM risk.risk_assessment WHERE id = ?", assessment)).containsExactly("HOLD");

        assertThat(column("SELECT kind || ',' || status || ',' || subject_kind || ',' || rule_code || ',' || severity"
                + " FROM risk.risk_alert WHERE subject_ref = ?", customer.party().toString()))
                .containsExactly("ACCOUNT_TAKEOVER,ATTACHED,PARTY,RECENT_CREDENTIAL_CHANGE,HIGH");
        // P13-TSK-016: one ATO case - opened by the alert's folding, the hold's assessment attached by CaseOpener.
        assertThat(column("SELECT kind || ',' || status FROM risk.risk_case WHERE subject_ref = ?",
                customer.party().toString())).containsExactly("ATO,OPEN");
        assertThat(count("SELECT count(*) FROM risk.risk_case_event e JOIN risk.risk_case c ON c.id = e.case_id"
                + " WHERE c.subject_ref = ? AND e.assessment_id = ?", customer.party().toString(), assessment))
                .as("the hold's assessment is the case's evidence").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND window_kind = 'D1'"
                + " AND window_start = ? AND ? = ANY (evidence_assessment_ids)", customer.party().toString(),
                java.sql.Timestamp.from(WindowStarts.start(WindowKind.D1, changedAt)), assessment))
                .as("keyed by the latest change's own day, the assessment its evidence").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert a JOIN risk.risk_signal s"
                + " ON s.id = ANY (a.evidence_signal_ids) WHERE a.subject_ref = ? AND s.source_event_id = ?",
                customer.party().toString(), changed.eventId().value())).isEqualTo(1);
        assertThat(alertsRaised(customer)).isEqualTo(1);

        Withdrawals.Initiated retry = withdraw(customer, 10_00, key);
        assertThat(retry.replayed()).isTrue();
        Withdrawals.Initiated again = withdraw(customer, 20_00, someKey());
        assertThat(again.status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(alerts(customer)).as("one sequence, one alert (INV-RSK-06)").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ?", customer.party().toString()))
                .as("one sequence, one ATO case (P13-TSK-016)").isEqualTo(1);
        assertThat(alertsRaised(customer)).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("ten concurrent withdrawals after one credential change raise one alert: each held, or refused by a"
            + " recorded budget breach (R2a queued past 250 ms) - never a second alert")
    void tenConcurrentWithdrawalsOfTheSequenceRaiseOneAlert() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        deliver(credentialChanged, credentialChangedEvent(customer.identity(),
                Instant.now(CLOCK).minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)));
        List<Withdrawals.Initiated> answers = race(java.util.stream.IntStream.range(0, 10)
                .<Callable<Withdrawals.Initiated>>mapToObj(i -> () -> withdraw(customer, 1_00, someKey())).toList());
        long held = answers.stream().filter(answer -> answer.status() == WithdrawalStatus.HELD).count();
        long breached = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND fail_safe_reason ="
                + " 'RISK_UNAVAILABLE'", customer.party());
        assertThat(held + breached).as("every withdrawal held, or its breach recorded").isEqualTo(10);
        assertThat(held).as("the expected run holds them all").isEqualTo(10);
        assertThat(alerts(customer)).isEqualTo(1);
        assertThat(alertsRaised(customer)).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("the sequence outside the lookback is not held: a credential change 25 h ago (the 24 h rule) - the"
            + " withdrawal dispatches, nothing alerts")
    void theSequenceOutsideTheLookbackIsNotHeld() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        deliver(credentialChanged, credentialChangedEvent(customer.identity(),
                Instant.now(CLOCK).minus(Duration.ofHours(25)).truncatedTo(ChronoUnit.SECONDS)));
        Withdrawals.Initiated sent = withdraw(customer, 10_00, someKey());
        assertThat(sent.status()).isNotEqualTo(WithdrawalStatus.HELD);
        assertThat(column("SELECT decision FROM risk.risk_assessment WHERE id = ?", assessmentOf(sent)))
                .containsExactly("ALLOW");
        assertThat(alerts(customer)).isZero();
    }

    // ------------------------------------------------------------------ the retrospective leg

    @Test
    @Order(4)
    @DisplayName("a recovery without a movement alerts once: the RecoveryCompleted through the inbox pulls the party's"
            + " permit; the profile leg alerts RECENT_RECOVERY on the party with the signal as evidence; a second sweep,"
            + " on another instance, raises nothing new")
    void aRecoveryWithoutAMovementAlertsOnce() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        ReceivedEvent recovered = recoveryCompletedEvent(customer.identity(),
                Instant.now(CLOCK).minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS));
        deliver(recoveryCompleted, recovered);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile WHERE party_id = ?", customer.party()))
                .as("the hint enrolled the party, its permit pulled to now").isEqualTo(1);

        sweep(customer.party());
        sweep(customer.party());
        assertThat(column("SELECT kind || ',' || status || ',' || rule_code || ',' || cardinality(evidence_assessment_ids)"
                + " FROM risk.risk_alert WHERE subject_ref = ?", customer.party().toString()))
                .containsExactly("ACCOUNT_TAKEOVER,ATTACHED,RECENT_RECOVERY,0");
        assertThat(count("SELECT count(*) FROM risk.risk_alert a JOIN risk.risk_signal s"
                + " ON s.id = ANY (a.evidence_signal_ids) WHERE a.subject_ref = ? AND s.source_event_id = ?",
                customer.party().toString(), recovered.eventId().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert a JOIN risk.risk_policy_version v"
                + " ON v.id = a.policy_version_id WHERE a.subject_ref = ? AND v.operation = 'MONITORING'",
                customer.party().toString())).as("the MONITORING version raised it").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ?", customer.party()))
                .as("no movement was judged").isZero();
        assertThat(alertsRaised(customer)).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("a replayed signal raises nothing new: the RecoveryCompleted redelivered past the dedupe - no second"
            + " signal - and swept again, after a withdrawal T-eval judged on the same sequence: still one alert")
    void aReplayedSignalRaisesNothingNew() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        ReceivedEvent recovered = recoveryCompletedEvent(customer.identity(),
                Instant.now(CLOCK).minus(Duration.ofHours(4)).truncatedTo(ChronoUnit.SECONDS));
        deliver(recoveryCompleted, recovered);
        sweep(customer.party());
        forgetDedupe(recovered);
        deliver(recoveryCompleted, recovered);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?", recovered.eventId().value()))
                .isEqualTo(1);
        sweep(customer.party());
        assertThat(alerts(customer)).isEqualTo(1);
        assertThat(alertsRaised(customer)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the savepoint

    @Test
    @Order(6)
    @DisplayName("a raise blocked past the budget is undone behind its savepoint: another transaction's uncommitted"
            + " birth of the same key holds the unique; T-eval's insert waits 250 ms, 57014, its savepoint undone - the"
            + " withdrawal HELD all the same; the blocker rolls back and no alert stands")
    void aRaiseBehindABlockedKeyTimesOutAndTheWithdrawalStands() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        Instant changedAt = Instant.now(CLOCK).minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        ReceivedEvent changed = credentialChangedEvent(customer.identity(), changedAt);
        deliver(credentialChanged, changed);
        UUID signal = HeldWithdrawalFixtures.uuid("SELECT id FROM risk.risk_signal WHERE source_event_id = ?",
                changed.eventId().value());
        Withdrawals.Initiated held;
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            new JdbcRiskAlertStore().insert(blocker, RiskAlertId.next(IDS), NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER,
                    AccountTakeover.SEVERITY, SignalSubject.of(SubjectKind.PARTY, customer.party()),
                    RuleKind.RECENT_CREDENTIAL_CHANGE, alertWindow(changedAt), withdrawalVersion(),
                    new AlertEvidence(List.of(RiskSignalId.of(signal)), List.of(), List.of()),
                    CorrelationId.generate(IDS), CausationId.of("blocker")));
            held = withdraw(customer, 10_00, someKey());
            blocker.rollback();
        }
        assertThat(held.status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(column("SELECT decision FROM risk.risk_assessment WHERE id = ?", assessmentOf(held)))
                .containsExactly("HOLD");
        assertThat(alerts(customer)).as("T-eval's birth undone, the blocker's rolled back").isZero();
    }

    // ------------------------------------------------------------------ the engine, the inbox, the sweep

    /** The composition root's own engine, as the customer, outside a request: the actor's identity is judged. */
    private Withdrawals.Initiated withdraw(Customer customer, long minor, String key) throws Exception {
        Withdrawals.Resolved resolved = HeldWithdrawalFixtures.resolved(customer, EUR);
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(customer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return engine.withdraw(key, Money.ofMinorUnits(minor, EUR), uow -> resolved);
        }
    }

    /** One delivery as the shell drives it: the dedupe record and the handler in one transaction, on its own instance. */
    private InboxConsumer.Outcome deliver(RiskSignalIntake intake, ReceivedEvent event) throws SQLException {
        try (SimulatedInstance instance = SimulatedInstance.inAgreementWithTheServer();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        new Correlation(event.correlationId(), CausationId.of(event.eventId().value().toString())))) {
            InboxConsumer.Outcome outcome = inbox.consume(instance.connection(), new InboxKey(intake.consumerName(),
                    event.eventId().value().toString()), event.eventType(), connection -> intake.handle(connection, event));
            if (outcome == InboxConsumer.Outcome.CONTENDED) {
                instance.rollback();
            } else {
                instance.commit();
            }
            return outcome;
        }
    }

    /** The profile leg for {@code party}, in a transaction of its own - as a sweeper on another instance runs it. */
    private void sweep(UUID party) {
        // As the schedule runs it: the platform's scope (P13-TSK-016 - the leg's folds open cases under SYSTEM).
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            inOneTransaction(uow -> profileRecompute.recompute(uow, party, CorrelationId.generate(IDS)));
        }
    }

    private static ReceivedEvent credentialChangedEvent(UUID identity, Instant occurredAt) {
        return new ReceivedEvent(EventId.next(IDS), "identity.CredentialChanged", 1, 1, identity, "Identity", occurredAt,
                "identity", CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("p13-tsk-013"),
                "application/json", EventPayload.of().with("identityId", identity.toString()).toBytes());
    }

    private static ReceivedEvent recoveryCompletedEvent(UUID identity, Instant occurredAt) {
        UUID request = UUID.randomUUID();
        return new ReceivedEvent(EventId.next(IDS), "identity.RecoveryCompleted", 1, 1, request, "RecoveryRequest",
                occurredAt, "identity", CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("p13-tsk-013"),
                "application/json", EventPayload.of().with("recoveryRequestId", request.toString())
                        .with("identityId", identity.toString()).with("channelId", UUID.randomUUID().toString())
                        .toBytes());
    }

    private static void forgetDedupe(ReceivedEvent event) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement forget = migrator.prepareStatement(
                        "DELETE FROM platform.inbox_message WHERE consumer = ? AND dedupe_key = ?")) {
            forget.setString(1, RiskSignalIntake.CONSUMER);
            forget.setString(2, event.eventId().value().toString());
            assertThat(forget.executeUpdate()).as("the dedupe record existed").isEqualTo(1);
        }
    }

    private static UUID assessmentOf(Withdrawals.Initiated withdrawal) throws SQLException {
        return HeldWithdrawalFixtures.uuid("SELECT id FROM risk.risk_assessment WHERE operation = 'WITHDRAWAL'"
                + " AND command_ref = ?", withdrawal.id().value().toString());
    }

    private static long alerts(Customer customer) throws SQLException {
        return count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'ACCOUNT_TAKEOVER'",
                customer.party().toString());
    }

    private static long alertsRaised(Customer customer) throws SQLException {
        return count("SELECT count(*) FROM platform.outbox_event e WHERE e.event_type = 'risk.AlertRaised'"
                + " AND EXISTS (SELECT 1 FROM risk.risk_alert a WHERE a.id = e.aggregate_id AND a.subject_ref = ?)",
                customer.party().toString());
    }

    private static AlertWindow alertWindow(Instant anchor) {
        return AlertWindow.of(new com.finapp.risk.VelocityWindowKey(com.finapp.risk.VelocityDimension.PARTY,
                IDS.next().toString(), WindowKind.D1, WindowStarts.start(WindowKind.D1, anchor)));
    }

    private static RiskPolicyVersionId withdrawalVersion() throws SQLException {
        return RiskPolicyVersionId.of(HeldWithdrawalFixtures.uuid("SELECT id FROM risk.risk_policy_version"
                + " WHERE operation = ? AND status = 'ACTIVE'", "WITHDRAWAL"));
    }

    // ------------------------------------------------------------------ the policies

    private static void activate() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        RiskPolicyAdministration administration = new RiskPolicyAdministration(new JdbcRiskPolicyStore(),
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
        for (RiskOperation operation : List.of(RiskOperation.WITHDRAWAL, RiskOperation.MONITORING)) {
            RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                    RiskPolicyFixtures.v1(operation), "the account-takeover suite's version", employee(),
                    CorrelationId.generate(IDS)));
            inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated",
                    CorrelationId.generate(IDS)));
        }
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
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
