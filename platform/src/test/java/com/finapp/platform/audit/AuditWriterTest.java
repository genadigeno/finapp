package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

/**
 * The audit writer against a real PostgreSQL (ADR-0010, {@code INV-AUD-01}).
 *
 * <p><strong>Why a real database.</strong> The claim under test is about a transaction boundary:
 * that an action which rolls back takes its audit record with it, and that one which commits
 * cannot commit without it. A fake would demonstrate that the writer calls the methods it calls.
 *
 * <p>Connects as the <strong>application</strong> role, not the superuser, so the writer is
 * exercised through exactly the privileges it will have in production. A missing grant then
 * fails here rather than on the first privileged action in a deployed environment.
 */
@Tag("database")
class AuditWriterTest {

    private static final String TABLE = "platform.audit_record";
    private static final String ACTIONS = "audit_action_probe";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new SecureRandom());

    private static Connection application;
    private final AuditWriter<Connection> audit = new JdbcAuditWriter();

    @BeforeAll
    static void connect() throws SQLException {
        // The probe table needs DDL, which the application role deliberately does not have.
        // Created by the migrator; exercised by the application - the same split the real
        // system has, rather than granting the application role DDL to make a test convenient.
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS platform." + ACTIONS
                            + " (id BIGSERIAL PRIMARY KEY, tag TEXT NOT NULL)");
            statement.execute("GRANT SELECT, INSERT, DELETE ON platform." + ACTIONS + " TO finapp_app");
            statement.execute(
                    "GRANT USAGE ON SEQUENCE platform." + ACTIONS + "_id_seq TO finapp_app");
        }
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
    }

    @AfterAll
    static void dropProbeAndDisconnect() throws SQLException {
        if (application != null) {
            application.close();
        }
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS platform." + ACTIONS);
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.executeUpdate("DELETE FROM platform." + ACTIONS);
        }
        application.commit();
    }

    // -----------------------------------------------------------------
    // The transaction boundary — ADR-0010's central requirement
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an action that rolls back takes its audit record with it")
    void rollbackTakesTheAuditRecordWithIt() throws SQLException {
        // Otherwise the trail asserts that something happened which did not - and an audit
        // record for an action nobody performed is not a harmless extra row, it is evidence of
        // the wrong thing.
        AuditId id = AuditId.next(IDS);

        recordAction("kyc-approved");
        audit.append(application, record(id, "kyc.CaseApproved"));
        application.rollback();

        assertThat(actionCount()).as("the action rolled back").isZero();
        assertThat(exists(id)).as("and so did its audit record").isFalse();
    }

    @Test
    @DisplayName("an action that commits commits its audit record with it")
    void commitKeepsThemTogether() throws SQLException {
        // The other half. A rollback-only test would pass against a writer that never wrote
        // anything at all - the same reasoning as the outbox writer's pair of tests.
        AuditId id = AuditId.next(IDS);

        recordAction("kyc-approved");
        audit.append(application, record(id, "kyc.CaseApproved"));
        application.commit();

        assertThat(actionCount()).isEqualTo(1);
        assertThat(exists(id)).isTrue();
    }

    @Test
    @DisplayName("a failed audit write fails the action rather than being swallowed")
    void aFailedAppendMustNotLeaveTheActionCommittable() throws SQLException {
        // Written twice under one id. The point is not the duplicate but that the writer raises
        // rather than logging and returning: an action committed without its audit record leaves
        // no trace it happened, and an absent audit record is indistinguishable from an action
        // that never occurred.
        AuditId id = AuditId.next(IDS);
        audit.append(application, record(id, "kyc.CaseApproved"));

        assertThatExceptionOfType(AuditWriteException.class)
                .isThrownBy(() -> audit.append(application, record(id, "kyc.CaseApproved")))
                .withMessageContaining("must not commit");
        application.rollback();

        assertThat(exists(id)).isFalse();
    }

    @Test
    @DisplayName("an auto-commit connection is refused, because the record would commit alone")
    void anAutoCommitConnectionIsRefused() throws SQLException {
        try (Connection autoCommit = DatabaseRoles.application()) {
            assertThatExceptionOfType(AuditWriteException.class)
                    .isThrownBy(() -> audit.append(autoCommit, record(AuditId.next(IDS), "kyc.Probe")))
                    .withMessageContaining("auto-commit");
        }
    }

    @Test
    @DisplayName("concurrent instances all append, and none blocks another")
    void concurrentAppendsDoNotContend() throws Exception {
        // DOD-KERNEL requires concurrency behaviour to be proven rather than argued. The
        // property here is an absence: audit writes must NOT serialise against each other.
        //
        // That is worth pinning precisely because it is easy to lose. An audit write sits on the
        // critical path of every privileged action under ADR-0010, so anything that made two of
        // them contend - a shared sequence, a summary row, a uniqueness rule over anything but
        // the record's own id - would put a lock in front of every action in the platform, and
        // it would show up as unexplained latency rather than as a failure.
        int instances = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<AuditId> written = new ArrayList<>();
        try {
            List<Future<AuditId>> running = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                running.add(
                        pool.submit(
                                () -> {
                                    // A connection each: a test sharing one would serialise
                                    // itself and prove nothing about contention.
                                    try (Connection own = DatabaseRoles.application()) {
                                        own.setAutoCommit(false);
                                        AuditId id = AuditId.next(IDS);
                                        start.await();
                                        new JdbcAuditWriter().append(own, record(id, "audit.Concurrent"));
                                        own.commit();
                                        return id;
                                    }
                                }));
            }
            start.countDown();
            for (Future<AuditId> future : running) {
                // Comfortably longer than it can need. If these were contending, the failure
                // would be a timeout rather than a wrong answer.
                written.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(written).doesNotHaveDuplicates();
        for (AuditId id : written) {
            assertThat(exists(id)).as("every instance's record is present").isTrue();
        }
    }

    // -----------------------------------------------------------------
    // What gets written
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every field reaches the row, so the record answers all seven questions")
    void theWholeRecordIsPersisted() throws SQLException {
        AuditId id = AuditId.next(IDS);
        AuditRecord written =
                new AuditRecord(
                        id,
                        new Actor("employee-42", ActorType.EMPLOYEE),
                        OCCURRED,
                        "reconciliation.BreakResolved",
                        "ReconciliationBreak",
                        "break-7",
                        Optional.of("counterparty confirmed the missing leg"),
                        AuditOutcome.SUCCEEDED,
                        CorrelationId.of("audit-flow-1"),
                        Optional.of("status: OPEN -> RESOLVED"));

        audit.append(application, written);
        application.commit();

        try (PreparedStatement select =
                application.prepareStatement("SELECT * FROM " + TABLE + " WHERE audit_id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("actor_id")).isEqualTo("employee-42");
                assertThat(row.getString("actor_type")).isEqualTo("EMPLOYEE");
                assertThat(row.getTimestamp("occurred_at").toInstant()).isEqualTo(OCCURRED);
                assertThat(row.getString("operation")).isEqualTo("reconciliation.BreakResolved");
                assertThat(row.getString("target_type")).isEqualTo("ReconciliationBreak");
                assertThat(row.getString("target_id")).isEqualTo("break-7");
                assertThat(row.getString("reason")).isEqualTo("counterparty confirmed the missing leg");
                assertThat(row.getString("outcome")).isEqualTo("SUCCEEDED");
                assertThat(row.getString("correlation_id")).isEqualTo("audit-flow-1");
                assertThat(row.getString("change_summary")).isEqualTo("status: OPEN -> RESOLVED");
            }
        }
        application.commit();
    }

    @Test
    @DisplayName("an absent reason is stored as NULL, not as an empty string")
    void anAbsentReasonIsNull() throws SQLException {
        // "No reason was required" and "a reason was required and nobody gave one" must remain
        // distinguishable years later. Coercing the first to '' destroys that distinction
        // silently, and an auditor reading a column of empty strings cannot tell which they are
        // looking at.
        AuditId id = AuditId.next(IDS);

        audit.append(application, record(id, "kyc.CaseApproved"));
        application.commit();

        try (PreparedStatement select =
                application.prepareStatement(
                        "SELECT reason, change_summary FROM " + TABLE + " WHERE audit_id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("reason")).isNull();
                assertThat(row.getString("change_summary")).isNull();
            }
        }
        application.commit();
    }

    @Test
    @DisplayName("a denied action is recorded, since a refusal is what an auditor asks about")
    void aDeniedActionIsRecorded() throws SQLException {
        // An audit trail containing only successes describes a system nobody ever attacked.
        // INV-AUD-03 depends on a control being able to show it refused something.
        AuditId id = AuditId.next(IDS);

        audit.append(
                application,
                new AuditRecord(
                        id,
                        new Actor("employee-9", ActorType.EMPLOYEE),
                        OCCURRED,
                        "ledger.ManualAdjustmentRequested",
                        "LedgerAccount",
                        "account-1",
                        Optional.of("exceeds the four-eyes threshold"),
                        AuditOutcome.DENIED,
                        CorrelationId.of("audit-flow-2"),
                        Optional.empty()));
        application.commit();

        assertThat(exists(id)).isTrue();
    }

    // -----------------------------------------------------------------

    private static AuditRecord record(AuditId id, String operation) {
        return new AuditRecord(
                id,
                Actor.SYSTEM,
                OCCURRED,
                operation,
                "Probe",
                "target-1",
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                CorrelationId.of("audit-flow"),
                Optional.empty());
    }

    private static void recordAction(String tag) throws SQLException {
        try (PreparedStatement insert =
                application.prepareStatement(
                        "INSERT INTO platform." + ACTIONS + " (tag) VALUES (?)")) {
            insert.setString(1, tag);
            insert.executeUpdate();
        }
    }

    private static int actionCount() throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT count(*) FROM platform." + ACTIONS)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean exists(AuditId id) throws SQLException {
        try (PreparedStatement select =
                application.prepareStatement("SELECT 1 FROM " + TABLE + " WHERE audit_id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }
}
