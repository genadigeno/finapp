package com.finapp.platform.audit;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code INV-HIST-03}: audit records are append-only, enforced by database privilege.
 *
 * <p><strong>This is the task's acceptance criterion and the reason the role split exists.</strong>
 * The application role holds {@code INSERT} and {@code SELECT} on
 * {@code platform.audit_record} and nothing else. Not because the application has no reason to
 * update or delete, but because it must be <em>unable</em> to: an audit trail the application
 * can edit is worth less than none, since it invites confidence it has not earned. The next
 * writer is a job, an operator tool or a psql session, and none of them read our Java.
 *
 * <p><strong>Every assertion here depends on one precondition</strong> — that the connected role
 * cannot bypass privileges. A superuser ignores every permission check, so this entire class
 * would pass against a table with no grants at all. {@link DatabaseRoles#assertCannotBypassPrivileges}
 * is checked first for that reason, and it is not a formality: running the application as the
 * cluster superuser is exactly what this project did until this task.
 */
@Tag("database")
class AuditImmutabilityTest {

    private static final String TABLE = "platform.audit_record";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new SecureRandom());

    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.close();
        }
    }

    @Test
    @DisplayName("the connected role cannot bypass privileges, or nothing below proves anything")
    void thePreconditionHolds() throws SQLException {
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @Test
    @DisplayName("the application role can append and read, which is what an audit trail needs")
    void appendAndReadAreGranted() throws SQLException {
        // The positive half. Without it, revoking INSERT as well would pass every denial test
        // below while making the audit trail unwritable - a "more secure" table that records
        // nothing, which fails INV-AUD-01 rather than satisfying INV-HIST-03.
        DatabaseRoles.assertCannotBypassPrivileges(application);
        AuditId id = AuditId.next(IDS);

        new JdbcAuditWriter().append(application, record(id, ProbeAuditAction.KYC_CASE_APPROVED));
        application.commit();

        assertThat(operationOf(id)).isEqualTo("kyc.CaseApproved");
    }

    @Test
    @DisplayName("UPDATE is denied at the privilege level")
    void updateIsDenied() throws SQLException {
        DatabaseRoles.assertCannotBypassPrivileges(application);
        AuditId id = AuditId.next(IDS);
        new JdbcAuditWriter().append(application, record(id, ProbeAuditAction.KYC_CASE_APPROVED));
        application.commit();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                execute(
                                        "UPDATE " + TABLE + " SET outcome = 'FAILED' WHERE audit_id = '"
                                                + id.value() + "'"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        assertThat(outcomeOf(id)).as("the record is unchanged").isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("DELETE is denied at the privilege level")
    void deleteIsDenied() throws SQLException {
        DatabaseRoles.assertCannotBypassPrivileges(application);
        AuditId id = AuditId.next(IDS);
        new JdbcAuditWriter().append(application, record(id, ProbeAuditAction.KYC_CASE_APPROVED));
        application.commit();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () -> execute("DELETE FROM " + TABLE + " WHERE audit_id = '" + id.value() + "'"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        assertThat(operationOf(id)).as("the record is still there").isEqualTo("kyc.CaseApproved");
    }

    @Test
    @DisplayName("UPDATE is denied on every column, not merely on the one this test happens to set")
    void updateIsDeniedOnEveryColumn() throws SQLException {
        // P0-TST-007. Table-level denial is not the whole story: PostgreSQL supports COLUMN-level
        // grants, and a single `GRANT UPDATE (reason)` makes that one column writable while
        // leaving every other assertion in this class true.
        //
        // That was not hypothetical. Granting it and rewriting a committed record's justification
        // - "original reason" to "rewritten after the fact" - left the entire audit suite green,
        // because the update test here sets `outcome` and the grants test reads
        // information_schema.table_privileges, where column grants do not appear at all. `reason`
        // is the worst column to lose: it is the justification for a privileged action, and the
        // field anyone covering their tracks would want to change.
        //
        // The column list comes from the catalogue rather than being written out, so a column
        // added by a later migration is covered without anyone remembering to add it here.
        DatabaseRoles.assertCannotBypassPrivileges(application);
        List<String> columns = columnsOf();
        // Named rather than counted. "More than five columns" would be satisfied by a query
        // that returned the wrong table; naming `reason` ties the guard to the column the
        // finding was actually about, and `outcome` to the one the older test already set.
        assertThat(columns)
                .as("the guard must see the audit table's real columns")
                .contains("reason", "outcome", "actor_id", "correlation_id");

        for (String column : columns) {
            // `SET c = c` needs UPDATE on exactly that column and no literal of any type, so it
            // asks the privilege question for every column the same way.
            assertThatExceptionOfType(SQLException.class)
                    .as("UPDATE must be denied on %s", column)
                    .isThrownBy(() -> execute("UPDATE " + TABLE + " SET " + column + " = " + column))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
            application.rollback();
        }
    }

    @Test
    @DisplayName("no column-level grant widens what the table-level grant allows")
    void noColumnLevelPrivilegeExists() throws SQLException {
        // The same hole from the other side, and the cheaper check: column grants are invisible
        // in table_privileges, so ApplicationRoleGrantsTest cannot see them however carefully it
        // reads. This is the view that can.
        DatabaseRoles.assertCannotBypassPrivileges(application);

        assertThat(columnPrivileges())
                .as("only INSERT and SELECT may reach any column of the audit trail")
                .containsExactly("INSERT", "SELECT");
    }

    @Test
    @DisplayName("TRUNCATE is denied too, which DELETE being denied does not imply")
    void truncateIsDenied() throws SQLException {
        // TRUNCATE is a separate privilege in PostgreSQL, not a form of DELETE. A grant of
        // SELECT, INSERT, DELETE would still leave the table truncatable if TRUNCATE had been
        // granted anywhere - and truncation is the most complete destruction of an audit trail
        // available. Asserted explicitly because "we denied DELETE" is the reasoning that
        // misses it.
        DatabaseRoles.assertCannotBypassPrivileges(application);

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute("TRUNCATE " + TABLE))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the application role cannot alter the table out of its own way")
    void schemaChangesAreDenied() throws SQLException {
        // A role that can DROP the constraint, DROP the table, or grant itself privileges has
        // not been restricted - it has been inconvenienced. This is the difference between an
        // append-only table and a table that is append-only until someone runs one statement.
        DatabaseRoles.assertCannotBypassPrivileges(application);

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute("DROP TABLE " + TABLE))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT audit_record_pk"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        // A self-grant does NOT raise. PostgreSQL reports "no privileges were granted" as a
        // WARNING and returns success, so asserting an exception here would have been asserting
        // the database's error-reporting choice rather than the security boundary - and would
        // have failed while the boundary held perfectly. The property that matters is the
        // outcome: after trying, the role still cannot update.
        execute("GRANT UPDATE ON " + TABLE + " TO finapp_app");
        application.commit();

        assertThatExceptionOfType(SQLException.class)
                .as("a role that can grant itself privileges has been inconvenienced, not restricted")
                .isThrownBy(() -> execute("UPDATE " + TABLE + " SET outcome = 'FAILED'"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        assertThat(privilegesOnAuditTable())
                .as("the granted privilege set is unchanged")
                .isEqualTo("INSERT,SELECT");
    }

    @Test
    @DisplayName("the application role holds no DDL in the schema at all")
    void theRoleCannotCreateTables() throws SQLException {
        // USAGE on the schema is granted; CREATE is not. Without this, the role could create a
        // table of its own beside the audit trail - not a breach of INV-HIST-03 by itself, but
        // an application role with DDL is one migration away from being able to alter anything.
        DatabaseRoles.assertCannotBypassPrivileges(application);

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute("CREATE TABLE platform.probe_should_not_exist (id INT)"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    // -----------------------------------------------------------------

    /** PostgreSQL's SQLState for a refused privilege. Locale-independent, unlike the message. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** Every column of the audit table, from the catalogue rather than from a list. */
    private static List<String> columnsOf() throws SQLException {
        return query(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'platform' AND table_name = 'audit_record' "
                        + "ORDER BY ordinal_position");
    }

    /** The distinct privileges the connected role holds on any column of the audit table. */
    private static List<String> columnPrivileges() throws SQLException {
        return query(
                "SELECT DISTINCT privilege_type FROM information_schema.column_privileges "
                        + "WHERE table_schema = 'platform' AND table_name = 'audit_record' "
                        + "AND grantee = current_user ORDER BY privilege_type");
    }

    private static List<String> query(String sql) throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } finally {
            application.commit();
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static AuditRecord record(AuditId id, AuditableAction operation) {
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

    /** What the application role actually holds, read from the catalogue rather than assumed. */
    private static String privilegesOnAuditTable() throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) "
                                        + "FROM information_schema.table_privileges "
                                        + "WHERE table_schema = 'platform' "
                                        + "AND table_name = 'audit_record' "
                                        + "AND grantee = current_user")) {
            rows.next();
            return rows.getString(1);
        } finally {
            application.commit();
        }
    }

    private static String operationOf(AuditId id) throws SQLException {
        return column(id, "operation");
    }

    private static String outcomeOf(AuditId id) throws SQLException {
        return column(id, "outcome");
    }

    private static String column(AuditId id, String name) throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT " + name + " FROM " + TABLE + " WHERE audit_id = '"
                                        + id.value() + "'")) {
            assertThat(rows.next()).as("the audit record must exist").isTrue();
            return rows.getString(1);
        } finally {
            application.commit();
        }
    }
}
