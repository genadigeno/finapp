package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.app.architecture.ReportingRoleIsSelectOnlyTest;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reporting role at a real database (`P15-TSK-021`; C13; {@code INV-ACC-14} at the privilege level;
 * {@code THREAT_MODEL.md} T7, T44). Owed to the exit database pass (C14), walked by hand at the task's gate.
 *
 * <p>Connected as {@code finapp_reporting} with no {@code READ ONLY} transaction - the guard this role adds is the
 * one that holds when the transaction's guard is gone. Every write is refused {@code 42501} by the role; every table
 * of the register is readable; a restricted table is not; the catalogue holds {@code SELECT} on the register and
 * nothing else. That production definitions generate as the role is the definition suites' (their generators open
 * {@code DatabaseRoles::reporting}, as production's open the reporting pool).
 */
@Tag("database")
@DisplayName("the reporting role reads the register and cannot write (P15-TSK-021)")
class ReportingRoleDatabaseTest {

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    @DisplayName("a planted write in generation - outside READ ONLY, as the role - is refused by the role: INSERT,"
            + " UPDATE, DELETE, TRUNCATE and DDL on the journal and the run")
    void aPlantedWriteIsRefusedByTheRole() throws SQLException {
        try (Connection reporting = DatabaseRoles.reporting()) {
            DatabaseRoles.assertCannotBypassPrivileges(reporting);
            reporting.setAutoCommit(false);
            assertThat(reporting.isReadOnly()).as("no READ ONLY transaction: the role alone refuses").isFalse();
            for (String write : List.of(
                    "INSERT INTO ledger.journal_entry DEFAULT VALUES",
                    "UPDATE ledger.journal_line SET direction = direction WHERE false",
                    "DELETE FROM accounting.report_run WHERE false",
                    "UPDATE accounting.report_run SET status = status WHERE false",
                    "TRUNCATE accounting.accounting_period",
                    "INSERT INTO platform.audit_record DEFAULT VALUES",
                    "CREATE TABLE ledger.planted (id int)",
                    "CREATE TEMPORARY TABLE planted (id int)")) {
                assertThatExceptionOfType(SQLException.class).as(write)
                        .isThrownBy(() -> execute(reporting, write))
                        .satisfies(refused -> assertThat(refused.getSQLState()).as(write).isEqualTo(INSUFFICIENT_PRIVILEGE));
                reporting.rollback();
            }
        }
    }

    @Test
    @DisplayName("every table of the register is readable as the role")
    void everyRegisterTableIsReadable() throws SQLException {
        try (Connection reporting = DatabaseRoles.reporting()) {
            for (String table : new TreeSet<>(ReportingRoleIsSelectOnlyTest.REGISTER)) {
                try (Statement read = reporting.createStatement();
                        ResultSet rows = read.executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(rows.next()).as(table).isTrue();
                }
            }
            try (Statement read = reporting.createStatement();
                    ResultSet day = read.executeQuery("SELECT lending.business_date(now(), 'UTC')")) {
                assertThat(day.next()).as("the safeguarding return's function").isTrue();
            }
        }
    }

    @Test
    @DisplayName("a restricted table, and a table of a granted schema outside the register, are refused")
    void aRestrictedTableIsRefused() throws SQLException {
        try (Connection reporting = DatabaseRoles.reporting()) {
            reporting.setAutoCommit(false);
            for (String read : List.of(
                    "SELECT count(*) FROM identity.credential",
                    "SELECT count(*) FROM identity.mfa_enrolment",
                    "SELECT count(*) FROM platform.audit_record",
                    "SELECT count(*) FROM kyc.kyc_document",
                    "SELECT count(*) FROM ledger.account_balance",
                    "SELECT count(*) FROM lending.repayment",
                    "SELECT count(*) FROM accounting.regulatory_submission")) {
                assertThatExceptionOfType(SQLException.class).as(read)
                        .isThrownBy(() -> execute(reporting, read))
                        .satisfies(refused -> assertThat(refused.getSQLState()).as(read).isEqualTo(INSUFFICIENT_PRIVILEGE));
                reporting.rollback();
            }
        }
    }

    @Test
    @DisplayName("the catalogue: SELECT on exactly the register, no column grant beyond it, no membership, no right")
    void theCatalogueHoldsSelectOnTheRegister() throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            assertThat(rows(owner, "SELECT DISTINCT privilege_type FROM information_schema.role_table_grants"
                    + " WHERE grantee = 'finapp_reporting'")).containsExactly("SELECT");
            assertThat(new TreeSet<>(rows(owner, "SELECT table_schema || '.' || table_name FROM"
                    + " information_schema.role_table_grants WHERE grantee = 'finapp_reporting'")))
                    .isEqualTo(new TreeSet<>(ReportingRoleIsSelectOnlyTest.REGISTER));
            assertThat(rows(owner, "SELECT DISTINCT privilege_type FROM information_schema.column_privileges"
                    + " WHERE grantee = 'finapp_reporting'")).containsOnly("SELECT");
            assertThat(rows(owner, "SELECT m.roleid::regrole::text FROM pg_auth_members m"
                    + " WHERE m.member = 'finapp_reporting'::regrole")).as("no role membership").isEmpty();
            assertThat(rows(owner, "SELECT rolsuper || ',' || rolcreatedb || ',' || rolcreaterole || ',' || rolinherit"
                    + " || ',' || rolbypassrls || ',' || rolreplication FROM pg_roles WHERE rolname = 'finapp_reporting'"))
                    .containsExactly("false,false,false,false,false,false");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }
}
