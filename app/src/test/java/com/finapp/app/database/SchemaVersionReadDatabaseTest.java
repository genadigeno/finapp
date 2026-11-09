package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.app.database.SchemaVersionGuard.Verdict;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The real read, as the role the application really is (`P15-TSK-005`): the database case the backlog entry owes (C14), run at
 * the exit's database pass, {@code P15-TST-002}.
 */
@Tag("database")
@SpringBootTest
@DisplayName("the guard's read works through the application's own privileges (P15-TSK-005)")
class SchemaVersionReadDatabaseTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired private DataSource dataSource;
    @Autowired private ExpectedSchemaVersions expected;
    @Autowired private SchemaVersionGuard guard;

    @Test
    @DisplayName("as finapp_app, every schema reads at the version this build expects, and the guard is ready")
    void theRealReadAnswersEverySchema() throws SQLException {
        Map<String, SchemaVersion> applied = new JdbcAppliedSchemaVersions(dataSource).read();

        assertThat(new TreeSet<>(applied.keySet())).as("every schema's history is reachable").containsAll(expected.versions().keySet());
        expected.versions().forEach((schema, version) -> assertThat(applied.get(schema)).as(schema).isEqualByComparingTo(version));
        guard.refreshNow();
        assertThat(guard.verdict()).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("the application role still cannot read any history table: the function is its only view of them")
    void theHistoryTableStaysClosed() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeQuery("SELECT * FROM ledger.flyway_schema_history"))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        }
    }

    @Test
    @DisplayName("only the application role may execute it")
    void onlyTheApplicationRoleMayExecute() throws SQLException {
        try (Connection connection = DatabaseRoles.migrator();
                Statement statement = connection.createStatement();
                ResultSet answer = statement.executeQuery("""
                        SELECT has_function_privilege('finapp_app', 'platform.applied_migration_versions()', 'EXECUTE'),
                               has_function_privilege('finapp_reporting', 'platform.applied_migration_versions()', 'EXECUTE'),
                               has_function_privilege('public', 'platform.applied_migration_versions()', 'EXECUTE')
                        """)) {
            assertThat(answer.next()).isTrue();
            assertThat(answer.getBoolean(1)).as("the application").isTrue();
            assertThat(answer.getBoolean(2)).as("reporting").isFalse();
            assertThat(answer.getBoolean(3)).as("everyone else").isFalse();
        }
    }

    @Test
    @DisplayName("a failed migration's row is not an applied version: success = false, as a non-transactional failure leaves")
    void aFailedRowIsNotApplied() throws SQLException {
        try (Connection connection = DatabaseRoles.migrator(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                    INSERT INTO ledger.flyway_schema_history
                        (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                    VALUES (2147483000, '999999', 'a probe', 'SQL', 'V999999__probe.sql', 0, current_user, 0, false)
                    """);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM platform.applied_migration_versions() WHERE schema_name = 'ledger' AND version = '999999'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            connection.rollback();
        }
    }
}
