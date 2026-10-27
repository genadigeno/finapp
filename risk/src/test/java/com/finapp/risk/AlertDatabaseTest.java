package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The born-once alert against a real database (`P13-TSK-015`; {@code risk V010}; {@code PHASE_13_PLAN.md} sections
 * 7.4, 13.2 scenarios 1, 2, 8, 15; {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-AUD-04}): two and ten instances
 * raising one alert write one row and one event; the same event twice and a reprocessed batch raise nothing new; an
 * alert born before any case stays queued {@code OPEN}; a suppression needs a second person - the domain refusing the
 * proposer and {@code risk_alert_suppression_four_eyes} refusing it alone; ten approvers suppress once; an alert is never
 * deleted by any role, and every other edge is refused by the machine. <strong>Written and owed</strong> (owner decision
 * C5).
 *
 * <p>Each case keys on a fresh subject; the rule alerts name one policy version PROPOSED for the suite (never activated
 * - the alert's foreign key needs a row, not a version in force).
 */
@Tag("database")
@DisplayName("the born-once alert against the database: one row per raise, four eyes by CHECK, never deleted"
        + " (P13-TSK-015)")
class AlertDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final RiskPolicyAdministration ADMINISTRATION = new RiskPolicyAdministration(POLICY_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final JdbcRiskAlertStore STORE = new JdbcRiskAlertStore();
    private static final Map<String, UUID> PARTY_OF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Alerts ALERTS = new Alerts(STORE, AlertFolding.NONE,
            (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id())), RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
    /** A case to attach to (risk V012's foreign key) - opened by hand, never folded into. */
    private static final Cases CASES = new Cases(new JdbcRiskCaseStore(), STORE,
            (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id())), RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String CHECK_VIOLATION = "23514";
    /** Today's D1 window start on the database clock - the alignment the CHECK admits. */
    private static final String TODAY =
            "date_bin(interval '1 day', clock_timestamp(), TIMESTAMPTZ '1970-01-05 00:00:00+00')";

    @Test
    @DisplayName("scenario 2: two instances raise one alert concurrently - one row, one BORN, one ALREADY, one"
            + " risk.AlertRaised")
    void twoInstancesRaiseOnce() throws Exception {
        assertRaisedOnceBy(2);
    }

    @Test
    @DisplayName("ten instances raise one alert concurrently - one row, one BORN, nine ALREADY answering it, one event")
    void tenInstancesRaiseOnce() throws Exception {
        assertRaisedOnceBy(10);
    }

    @Test
    @DisplayName("scenario 1: the same event delivered twice, the second after the first committed - the same evidence,"
            + " the same window: one row, one event")
    void theSameEventTwiceRaisesOnce() throws SQLException {
        SignalSubject subject = freshParty();
        NewAlert alert = ato(subject, AlertWindow.containing(WindowKind.D1, Instant.now()));
        Alerts.Raised first = inOneTransaction(uow -> ALERTS.raise(uow, alert));
        Alerts.Raised second = inOneTransaction(uow -> ALERTS.raise(uow, alert));
        assertThat(first.outcome()).isEqualTo(Alerts.Outcome.BORN);
        assertThat(second.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(second.alert().id()).isEqualTo(first.alert().id());
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .isEqualTo(1);
        assertThat(events(first.alert().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 8: a batch re-run from an earlier point - every alert's window from its evidence's stamp -"
            + " raises nothing new; the new evidence alone raises")
    void reprocessingRaisesNothingNew() throws SQLException {
        SignalSubject subject = freshParty();
        Instant stamp = Instant.now().minusSeconds(3 * 86_400);
        List<NewAlert> batch = List.of(
                structuring(subject, AlertWindow.containing(WindowKind.D1, stamp)),
                structuring(subject, AlertWindow.containing(WindowKind.D1, stamp.plusSeconds(86_400))));
        inOneTransaction(uow -> ALERTS.raiseAll(uow, batch));
        List<NewAlert> rerun = new ArrayList<>(batch);
        rerun.add(structuring(subject, AlertWindow.containing(WindowKind.D1, stamp.plusSeconds(2 * 86_400))));
        List<Alerts.Raised> answers = inOneTransaction(uow -> ALERTS.raiseAll(uow, rerun));
        assertThat(answers).extracting(Alerts.Raised::outcome)
                .containsExactly(Alerts.Outcome.ALREADY, Alerts.Outcome.ALREADY, Alerts.Outcome.BORN);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an alert born before any case exists stays OPEN - in the queue, oldest first - and its re-raise keeps"
            + " one OPEN row")
    void anOpenAlertIsQueuedUntilACaseExists() throws SQLException {
        SignalSubject subject = freshParty();
        NewAlert alert = ato(subject, AlertWindow.containing(WindowKind.H1, Instant.now()));
        Alerts.Raised raised = inOneTransaction(uow -> ALERTS.raise(uow, alert));
        inOneTransaction(uow -> ALERTS.raise(uow, alert));
        assertThat(raised.folded()).isEqualTo(AlertFolding.Folded.QUEUED);
        assertThat(column("SELECT status FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .containsExactly("OPEN");
        assertThat(column("SELECT id::text FROM risk.risk_alert WHERE status = 'OPEN' ORDER BY raised_at, id"))
                .contains(raised.alert().id().value().toString());
    }

    @Test
    @DisplayName("scenario 15's alert half: the proposer's approval is refused by the domain and recorded FAILED - and"
            + " with the domain bypassed, risk_alert_suppression_four_eyes refuses it alone (23514); a SYSTEM proposer"
            + " is refused by risk_alert_suppression_by_persons; a second person suppresses")
    void aSuppressionNeedsASecondPerson() throws Exception {
        RiskAlert alert = open();
        Actor proposer = analyst();
        inOneTransaction(uow -> ALERTS.proposeSuppression(uow, alert.id(), "FALSE_POSITIVE", "payroll pattern",
                proposer, correlation()));
        assertThatThrownBy(() -> inOneTransaction(uow -> ALERTS.approveSuppression(uow, alert.id(), "mine", proposer,
                correlation()))).isInstanceOf(Alerts.SelfApprovalRefused.class);

        // The domain bypassed: the store's own statement, the proposer as approver - the CHECK alone refuses it.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            SQLException refused = sqlFailure(() -> {
                try (PreparedStatement update = app.prepareStatement(JdbcRiskAlertStore.APPROVE)) {
                    update.setString(1, proposer.id());
                    update.setString(2, proposer.type().name());
                    update.setString(3, "the CHECK alone");
                    update.setObject(4, alert.id().value());
                    update.executeQuery().close();
                }
            });
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("risk_alert_suppression_four_eyes");
            app.rollback();
        }

        RiskAlert other = open();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            SQLException refused = sqlFailure(() -> {
                try (PreparedStatement update = app.prepareStatement(JdbcRiskAlertStore.PROPOSE)) {
                    update.setString(1, "RULE_DEFECT");
                    update.setString(2, "the system dedupes");
                    update.setString(3, "system");
                    update.setString(4, ActorType.SYSTEM.name());
                    update.setObject(5, other.id().value());
                    update.executeQuery().close();
                }
            });
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("risk_alert_suppression_by_persons");
            app.rollback();
        }

        Alerts.Decided decided = inOneTransaction(uow -> ALERTS.approveSuppression(uow, alert.id(), "agreed",
                analyst(), correlation()));
        assertThat(decided.alert().status()).isEqualTo(AlertStatus.SUPPRESSED);
        assertThat(column("SELECT status || ',' || (suppression_decided_at IS NOT NULL) || ','"
                + " || (suppression_decided_by <> suppression_proposed_by) FROM risk.risk_alert WHERE id = '"
                + alert.id().value() + "'")).containsExactly("SUPPRESSED,true,true");
    }

    @Test
    @DisplayName("ten approvers of one proposal, each in its own transaction: one SUPPRESSED, nine AlertNotOpen behind"
            + " the row lock - one risk.AlertSuppressed")
    void tenApproversSuppressOnce() throws Exception {
        RiskAlert alert = open();
        inOneTransaction(uow -> ALERTS.proposeSuppression(uow, alert.id(), "ACTIVITY_EXPLAINED", "documents on file",
                analyst(), correlation()));
        List<Object> answers = race(10, () -> {
            Actor approver = analyst();
            try {
                return inOneTransaction(uow -> ALERTS.approveSuppression(uow, alert.id(), "four eyes", approver,
                        correlation()));
            } catch (Alerts.AlertNotOpen stale) {
                return stale;
            }
        });
        assertThat(answers).filteredOn(Alerts.Decided.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(Alerts.AlertNotOpen.class::isInstance).hasSize(9);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.AlertSuppressed'"
                + " AND target_id = '" + alert.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an alert is never deleted: the application holds no DELETE and no UPDATE of a fact; the owner is"
            + " refused DELETE, TRUNCATE and a fact's edit by the trigger")
    void anAlertIsNeverDeleted() throws SQLException {
        RiskAlert alert = open();
        String where = " WHERE id = '" + alert.id().value() + "'";
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "DELETE FROM risk.risk_alert" + where, INSUFFICIENT_PRIVILEGE);
            refused(app, "UPDATE risk.risk_alert SET kind = 'FRAUD'" + where, INSUFFICIENT_PRIVILEGE);
            refused(app, "UPDATE risk.risk_alert SET raised_at = now()" + where, INSUFFICIENT_PRIVILEGE);
            refused(app, "TRUNCATE risk.risk_alert", INSUFFICIENT_PRIVILEGE);
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            refusedWithMessage(migrator, "DELETE FROM risk.risk_alert" + where, "never deleted");
            // CASCADE: since risk V012 the case tables reference the alert, and a plain TRUNCATE is refused by the
            // foreign key before any trigger runs (P13-TST-001's run of this owed suite) - with them in the statement,
            // the refusal is a trigger's, each of which says "never deleted or truncated".
            refusedWithMessage(migrator, "TRUNCATE risk.risk_alert CASCADE", "never deleted");
            refusedWithMessage(migrator, "UPDATE risk.risk_alert SET severity = 'LOW'" + where, "facts never change");
        }
        // With CASCADE any truncated table's trigger may be the one that refuses, so the alert's OWN truncate trigger is
        // pinned present and enabled (P13-DOC-001, the review's A8-7 on the repair that added the CASCADE).
        assertThat(count("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'risk.risk_alert'::regclass"
                + " AND tgname = 'risk_alert_is_never_truncated' AND tgenabled <> 'D' AND (tgtype & 32) <> 0"))
                .as("risk.risk_alert's own BEFORE TRUNCATE trigger").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert" + where)).isEqualTo(1);
    }

    @Test
    @DisplayName("the machine refuses every other edge for every writer: out of ATTACHED or SUPPRESSED, a birth not"
            + " OPEN, a window off its boundary, an operational alert attached, a proposal over a standing one")
    void theMachineRefusesEveryOtherEdge() throws SQLException {
        RiskAlert attached = open();
        // A real case: since risk V012 the attachment references risk_case, so a made-up id is refused by the foreign
        // key (P13-TST-001's run of this owed suite).
        RiskCaseId caseId = inOneTransaction(uow -> CASES.open(uow, Cases.OpenRequest.of(CaseKind.FRAUD,
                freshParty()), "an attachment's case", analyst(), correlation())).riskCase().id();
        inOneTransaction(uow -> ALERTS.attach(uow, attached.id(), caseId));
        RiskAlert proposedOnce = open();
        inOneTransaction(uow -> ALERTS.proposeSuppression(uow, proposedOnce.id(), "DATA_DEFECT", "the feed was wrong",
                analyst(), correlation()));
        try (Connection migrator = DatabaseRoles.migrator()) {
            refusedWithMessage(migrator, "UPDATE risk.risk_alert SET status = 'OPEN', attached_case_id = NULL"
                    + " WHERE id = '" + attached.id().value() + "'", "never moves");
            refusedWithMessage(migrator, "UPDATE risk.risk_alert SET suppression_proposed_by = 'someone-else'"
                    + " WHERE id = '" + proposedOnce.id().value() + "'", "a proposal onto none");
            refusedWithMessage(migrator, insertSql("ATTACHED", "D1", TODAY), "born OPEN");
            refused(migrator, insertSql("OPEN", "D1", "clock_timestamp()"), CHECK_VIOLATION);
            refused(migrator, "INSERT INTO risk.risk_alert (id, kind, severity, subject_kind, subject_ref, rule_code,"
                    + " window_kind, window_start, policy_version_id, evidence_signal_ids, evidence_assessment_ids,"
                    + " evidence_journal_entry_ids, status, raised_at, correlation_id, causation_id) VALUES"
                    + " (gen_random_uuid(), 'AML', 'LOW', 'PARTY', gen_random_uuid()::text, 'UNCLASSIFIED_TRANSACTION',"
                    + " 'D1', " + TODAY + ", NULL, '{}', '{}', '{}', 'OPEN', clock_timestamp(), 'flow', 'cause')",
                    CHECK_VIOLATION);
        }
        // The positive control for the shared name: a SCREENING_HIT alert is admitted - since risk V018 (P13-TSK-022's
        // review) a fact's alert, its own code the rule and its one signal its identity, never a policy rule's (the
        // first run of this owed suite, P13-TST-001, met the domain refusing the rule-kind shape this case was written in).
        Alerts.Raised screening = inOneTransaction(uow -> ALERTS.raise(uow, NewAlert.ofFact(AlertKind.SCREENING_HIT,
                AlertSeverity.CRITICAL, freshParty(), AlertWindow.containing(WindowKind.D1, Instant.now()),
                RiskSignalId.of(IDS.next()), correlation(), CausationId.of("alert-suite"))));
        assertThat(screening.outcome()).isEqualTo(Alerts.Outcome.BORN);
        RiskAlert operational = inOneTransaction(uow -> ALERTS.raise(uow, NewAlert.operational(
                AlertKind.UNCLASSIFIED_TRANSACTION, AlertSeverity.LOW, freshParty(),
                AlertWindow.containing(WindowKind.D1, Instant.now()), AlertEvidence.journalEntries(List.of(IDS.next())),
                correlation(), CausationId.of("alert-suite")))).alert();
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "UPDATE risk.risk_alert SET status = 'ATTACHED', attached_case_id = gen_random_uuid()"
                    + " WHERE id = '" + operational.id().value() + "'", CHECK_VIOLATION);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static void assertRaisedOnceBy(int instances) throws Exception {
        SignalSubject subject = freshParty();
        NewAlert alert = ato(subject, AlertWindow.containing(WindowKind.D1, Instant.now()));
        List<Alerts.Raised> answers = race(instances, () -> inOneTransaction(uow -> ALERTS.raise(uow, alert)));
        assertThat(answers).filteredOn(raised -> raised.outcome() == Alerts.Outcome.BORN).hasSize(1);
        assertThat(answers).filteredOn(raised -> raised.outcome() == Alerts.Outcome.ALREADY).hasSize(instances - 1);
        assertThat(answers).extracting(raised -> raised.alert().id()).containsOnly(answers.get(0).alert().id());
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = '" + subject.ref() + "'"))
                .isEqualTo(1);
        assertThat(events(answers.get(0).alert().id())).isEqualTo(1);
    }

    private static RiskPolicyVersionId policy() {
        return SuiteVersion.ID;
    }

    /** The suite's one policy version, proposed on first use (the holder idiom: the class loader's once). */
    private static final class SuiteVersion {
        static final RiskPolicyVersionId ID = inOneTransaction(uow -> ADMINISTRATION.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.TRANSFER), "the alert suite's version", analyst(), correlation()))
                .id();
    }

    private static NewAlert ato(SignalSubject subject, AlertWindow window) {
        return NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject, RuleKind.RECENT_CREDENTIAL_CHANGE,
                window, policy(), AlertEvidence.assessment(RiskAssessmentId.of(IDS.next())), correlation(),
                CausationId.of("alert-suite"));
    }

    private static NewAlert structuring(SignalSubject subject, AlertWindow window) {
        return NewAlert.ofRule(AlertKind.AML, AlertSeverity.MEDIUM, subject, RuleKind.STRUCTURING, window, policy(),
                AlertEvidence.journalEntries(List.of(IDS.next())), correlation(), CausationId.of("alert-suite"));
    }

    /** An open ATO alert of a fresh party. */
    private static RiskAlert open() {
        return inOneTransaction(uow -> ALERTS.raise(uow, ato(freshParty(),
                AlertWindow.containing(WindowKind.D1, Instant.now())))).alert();
    }

    private static String insertSql(String status, String windowKind, String windowStart) {
        return "INSERT INTO risk.risk_alert (id, kind, severity, subject_kind, subject_ref, rule_code, window_kind,"
                + " window_start, policy_version_id, evidence_signal_ids, evidence_assessment_ids,"
                + " evidence_journal_entry_ids, status, attached_case_id, attached_at, raised_at, correlation_id,"
                + " causation_id) VALUES (gen_random_uuid(), 'UNCLASSIFIED_TRANSACTION', 'LOW', 'PARTY',"
                + " gen_random_uuid()::text, 'UNCLASSIFIED_TRANSACTION', '" + windowKind + "', " + windowStart + ","
                + " NULL, '{}', '{}', '{}', '" + status + "', "
                + ("ATTACHED".equals(status) ? "gen_random_uuid(), clock_timestamp()" : "NULL, NULL")
                + ", clock_timestamp(), 'flow', 'cause')";
    }

    private static SignalSubject freshParty() {
        return SignalSubject.of(SubjectKind.PARTY, IDS.next());
    }

    private static Actor analyst() {
        Actor actor = new Actor("analyst-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        PARTY_OF.put(actor.id(), UUID.randomUUID());
        return actor;
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static long events(RiskAlertId alert) {
        try {
            return count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.AlertRaised'"
                    + " AND aggregate_id = '" + alert.value() + "'");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
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

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static void refusedWithMessage(Connection connection, String sql, String message) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(message);
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
