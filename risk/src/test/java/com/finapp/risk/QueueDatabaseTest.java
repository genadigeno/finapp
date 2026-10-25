package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The queues and the suppression's persons against a real database (`P13-TSK-018`; {@code risk V014};
 * {@code PHASE_13_PLAN.md} sections 7.4, 9, 11; {@code INV-RSK-07}, {@code INV-RSK-08}, {@code INV-AUD-04};
 * {@code DESIGN-018} D1, D4, D14): a keyset page under concurrent inserts neither skips nor repeats - every alert
 * committed before the pass is served once, in order, and the alerts born during it are served at most once; ten
 * approvers of one proposal - each a {@code CUSTOMER}-typed operator, as the session establishes every identity -
 * suppress once; {@code V014}'s widened {@code risk_alert_suppression_by_persons} admits a {@code CUSTOMER} proposer
 * and decider and refuses the platform alone, the domain bypassed; the case queue pages oldest first and the backlog
 * read counts the open cases. <strong>Written and owed</strong> (owner decision C5).
 *
 * <p>The container is shared: each pass starts after a marker the suite raised, and every assertion is about the
 * suite's own rows.
 */
@Tag("database")
@DisplayName("the queues and the suppression's persons against the database (P13-TSK-018)")
class QueueDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final JdbcRiskAlertStore ALERT_STORE = new JdbcRiskAlertStore();
    private static final JdbcRiskCaseStore CASE_STORE = new JdbcRiskCaseStore();
    private static final Map<String, UUID> PARTY_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final RiskActingParty ACTING = (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id()));
    private static final Alerts ALERTS = new Alerts(ALERT_STORE, AlertFolding.NONE, ACTING, RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
    private static final AlertQueue QUEUE = new AlertQueue(ALERT_STORE, ACTING, RiskSubjectParties.NONE,
            new JdbcAuditWriter(), IDS, CLOCK);
    private static final Cases CASES = new Cases(CASE_STORE, ALERT_STORE, ACTING, RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
    private static final CaseWorklist WORKLIST = new CaseWorklist(CASE_STORE, ALERT_STORE,
            new RiskActions(new JdbcRiskActionStore(), CASES, CASE_STORE, new JdbcRiskAssessmentStore(),
                    new ActionDispatch(NoOwners.ONE, NoOwners.ONE, NoOwners.ONE, NoOwners.ONE), new JdbcAuditWriter(),
                    new JdbcOutboxWriter(), IDS, CLOCK),
            ACTING, RiskSubjectParties.NONE, new JdbcAuditWriter(), IDS, CLOCK);

    @Test
    @DisplayName("a keyset page under concurrent inserts neither skips nor repeats: every alert committed before the"
            + " pass served once and in order, every alert born during it at most once, the cursor always the last"
            + " served")
    void aPageUnderInsertsNeitherSkipsNorRepeats() throws Exception {
        RiskAlert marker = open();
        List<RiskAlertId> before = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            before.add(open().id());
        }
        Actor reader = analyst(ActorType.CUSTOMER);
        AtomicBoolean paging = new AtomicBoolean(true);
        List<RiskAlertId> during = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService inserters = Executors.newFixedThreadPool(4);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                running.add(inserters.submit(() -> {
                    while (paging.get()) {
                        during.add(open().id());
                    }
                    return null;
                }));
            }
            List<RiskAlertId> served = new ArrayList<>();
            Optional<RiskAlertId> after = Optional.of(marker.id());
            while (after.isPresent() && served.size() < 2_000) {
                Optional<RiskAlertId> cursor = after;
                AlertQueue.Page page = inSnapshot(uow -> QUEUE.page(uow, AlertQueue.Filter.named(Optional.empty(),
                        Optional.empty()), cursor, 7, reader));
                page.alerts().forEach(alert -> served.add(alert.id()));
                page.next().ifPresent(next -> assertThat(next).isEqualTo(page.alerts().get(page.alerts().size() - 1)
                        .id()));
                after = page.next();
                if (served.size() > 70) {
                    paging.set(false);
                }
            }
            paging.set(false);
            for (Future<?> inserter : running) {
                inserter.get(2, TimeUnit.MINUTES);
            }
            assertThat(served).as("nothing served twice").doesNotHaveDuplicates();
            assertThat(served).as("every alert committed before the pass, once, in raised order")
                    .containsSubsequence(before);
            Set<RiskAlertId> mine = new LinkedHashSet<>(before);
            mine.addAll(during);
            assertThat(served.stream().filter(mine::contains).distinct().count())
                    .isEqualTo(served.stream().filter(mine::contains).count());
        } finally {
            paging.set(false);
            inserters.shutdownNow();
        }
    }

    @Test
    @DisplayName("ten approvers of one proposal - CUSTOMER-typed operators, as the session establishes every identity -"
            + " each in its own transaction: one SUPPRESSED, nine AlertNotOpen behind the row lock, one"
            + " risk.AlertSuppressed")
    void tenApproversSuppressOnce() throws Exception {
        RiskAlert alert = open();
        inOneTransaction(uow -> ALERTS.proposeSuppression(uow, alert.id(), "ACTIVITY_EXPLAINED", "documents on file",
                analyst(ActorType.CUSTOMER), correlation()));
        List<Object> answers = race(10, () -> {
            Actor approver = analyst(ActorType.CUSTOMER);
            try {
                return inOneTransaction(uow -> ALERTS.approveSuppression(uow, alert.id(), "agreed", approver,
                        correlation()).alert().status());
            } catch (Alerts.AlertNotOpen notOpen) {
                return "NOT_OPEN";
            }
        });
        assertThat(answers).filteredOn(AlertStatus.SUPPRESSED::equals).hasSize(1);
        assertThat(answers).filteredOn("NOT_OPEN"::equals).hasSize(9);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.AlertSuppressed'"
                + " AND target_id = '" + alert.id().value() + "'")).isEqualTo(1);
        assertThat(column("SELECT suppression_proposed_by_type || ',' || suppression_decided_by_type FROM"
                + " risk.risk_alert WHERE id = '" + alert.id().value() + "'")).containsExactly("CUSTOMER,CUSTOMER");
    }

    @Test
    @DisplayName("V014's persons, the domain bypassed: a CUSTOMER proposer and decider admitted by the store's own"
            + " statements; a SYSTEM proposer and a SERVICE decider refused by risk_alert_suppression_by_persons alone")
    void theWidenedPersonsCheck() throws Exception {
        RiskAlert alert = open();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement propose = app.prepareStatement(JdbcRiskAlertStore.PROPOSE)) {
                propose.setString(1, "FALSE_POSITIVE");
                propose.setString(2, "a person's proposal");
                propose.setString(3, UUID.randomUUID().toString());
                propose.setString(4, ActorType.CUSTOMER.name());
                propose.setObject(5, alert.id().value());
                propose.executeQuery().close();
            }
            SQLException byService = sqlFailure(() -> {
                try (PreparedStatement approve = app.prepareStatement(JdbcRiskAlertStore.APPROVE)) {
                    approve.setString(1, "service-1");
                    approve.setString(2, ActorType.SERVICE.name());
                    approve.setString(3, "a service decides");
                    approve.setObject(4, alert.id().value());
                    approve.executeQuery().close();
                }
            });
            assertThat(byService.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(byService.getMessage()).contains("risk_alert_suppression_by_persons");
            app.rollback();
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement propose = app.prepareStatement(JdbcRiskAlertStore.PROPOSE)) {
                propose.setString(1, "FALSE_POSITIVE");
                propose.setString(2, "a person's proposal");
                propose.setString(3, UUID.randomUUID().toString());
                propose.setString(4, ActorType.CUSTOMER.name());
                propose.setObject(5, alert.id().value());
                propose.executeQuery().close();
            }
            try (PreparedStatement approve = app.prepareStatement(JdbcRiskAlertStore.APPROVE)) {
                approve.setString(1, UUID.randomUUID().toString());
                approve.setString(2, ActorType.CUSTOMER.name());
                approve.setString(3, "a second person decides");
                approve.setObject(4, alert.id().value());
                try (ResultSet row = approve.executeQuery()) {
                    assertThat(row.next()).as("a CUSTOMER-typed decider is a person").isTrue();
                }
            }
            app.commit();
        }
        RiskAlert other = open();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            SQLException bySystem = sqlFailure(() -> {
                try (PreparedStatement propose = app.prepareStatement(JdbcRiskAlertStore.PROPOSE)) {
                    propose.setString(1, "RULE_DEFECT");
                    propose.setString(2, "the system dedupes");
                    propose.setString(3, "system");
                    propose.setString(4, ActorType.SYSTEM.name());
                    propose.setObject(5, other.id().value());
                    propose.executeQuery().close();
                }
            });
            assertThat(bySystem.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(bySystem.getMessage()).contains("risk_alert_suppression_by_persons");
            app.rollback();
        }
        assertThat(column("SELECT status FROM risk.risk_alert WHERE id = '" + alert.id().value() + "'"))
                .containsExactly("SUPPRESSED");
    }

    @Test
    @DisplayName("the case queue pages oldest first after a cursor on risk_case_open_queue; the backlog read counts the"
            + " open cases per kind with the oldest's age on the database clock")
    void theCaseQueueAndTheBacklog() throws Exception {
        Actor opener = analyst(ActorType.CUSTOMER);
        RiskCase marker = openCase(opener);
        List<RiskCaseId> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(openCase(opener).id());
        }
        Actor reader = analyst(ActorType.CUSTOMER);
        List<RiskCaseId> served = new ArrayList<>();
        Optional<RiskCaseId> after = Optional.of(marker.id());
        while (after.isPresent() && served.size() < 500) {
            Optional<RiskCaseId> cursor = after;
            CaseWorklist.Page page = inSnapshot(uow -> WORKLIST.page(uow, CaseWorklist.Filter.named(Optional.empty(),
                    Optional.of("FRAUD")), cursor, 2, reader));
            page.cases().forEach(riskCase -> served.add(riskCase.id()));
            after = page.next();
        }
        assertThat(served).doesNotHaveDuplicates().containsSubsequence(mine);
        List<RiskCaseStore.Backlog> backlog = inOneTransaction(CASE_STORE::backlog);
        assertThat(backlog).filteredOn(row -> row.kind() == CaseKind.FRAUD).singleElement().satisfies(row -> {
            assertThat(row.open()).isGreaterThanOrEqualTo(6);
            assertThat(row.oldestAgeSeconds()).isNotNegative();
        });
        CaseWorklist.CaseFile file = inSnapshot(uow -> WORKLIST.read(uow, mine.get(0), reader));
        assertThat(file.history()).isNotEmpty();
        inOneTransaction(uow -> {
            WORKLIST.record(uow, file, reader, correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.CaseRead'"
                + " AND target_id = '" + mine.get(0).value() + "'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    /** An open ATO alert of a fresh party, raised now. */
    private static RiskAlert open() {
        return inOneTransaction(uow -> ALERTS.raise(uow, NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH,
                SignalSubject.of(SubjectKind.PARTY, IDS.next()), RuleKind.RECENT_CREDENTIAL_CHANGE,
                AlertWindow.containing(WindowKind.D1, Instant.now()), SuiteVersion.ID,
                AlertEvidence.assessment(RiskAssessmentId.of(IDS.next())), correlation(),
                CausationId.of("queue-suite")))).alert();
    }

    /** A FRAUD case opened by hand on a fresh party. */
    private static RiskCase openCase(Actor opener) {
        return inOneTransaction(uow -> CASES.open(uow, Cases.OpenRequest.of(CaseKind.FRAUD, SignalSubject.of(
                SubjectKind.PARTY, IDS.next())), "the queue suite's case", opener, correlation()).riskCase());
    }

    private static Actor analyst(ActorType type) {
        Actor actor = new Actor(UUID.randomUUID().toString(), type);
        PARTY_OF.put(actor.id(), UUID.randomUUID());
        return actor;
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** The suite's one policy version, proposed on first use (the holder idiom). */
    private static final class SuiteVersion {
        static final RiskPolicyVersionId ID = inOneTransaction(uow -> new RiskPolicyAdministration(
                new JdbcRiskPolicyStore(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK).propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.TRANSFER), "the queue suite's version",
                new Actor("queue-suite", ActorType.EMPLOYEE), correlation())).id();
    }

    /** The owners' doors - never called: the queue suite dispatches nothing. */
    private enum NoOwners implements HeldWithdrawalActions, HeldPaymentActions, IdentityActions, MerchantActions {
        ONE;

        @Override
        public ActionOutcome release(UUID withdrawalId, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome deny(UUID withdrawalId, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome capture(UUID intentId, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome voidHold(UUID intentId, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome suspend(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome reinstate(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome revokeSessions(UUID partyId, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActionOutcome suspend(UUID merchantId, String idempotencyKey, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code work} on one read-only REPEATABLE READ snapshot - the desk's {@code inSnapshot}. */
    private static <R> R inSnapshot(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                return work.apply(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private interface SqlWork {
        void run() throws SQLException;
    }

    private static SQLException sqlFailure(SqlWork work) {
        try {
            work.run();
        } catch (SQLException failure) {
            return failure;
        }
        throw new AssertionError("the statement was expected to be refused");
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            while (row.next()) {
                values.add(row.getString(1));
            }
        }
        return values;
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
