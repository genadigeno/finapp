package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the application role can and cannot do to every table in the platform schema.
 *
 * <p><strong>Why this exists separately from the tests of each table.</strong> Those suites
 * connect as the bootstrap superuser, which ignores every permission check — so they prove the
 * code works and prove nothing at all about the grants. {@code V008} wrote a grant for each
 * existing table based on what its comment claimed it needed, and a grant nobody exercises is a
 * guess. This connects as the application role and checks both directions: that everything the
 * platform genuinely does still works, and that nothing else does.
 *
 * <p>The asymmetry matters. A grant that is too narrow fails loudly the first time the feature
 * runs. A grant that is too wide fails silently, forever, and is discovered by whoever exploits
 * it — which is why the denials below are asserted at least as carefully as the permissions.
 */
@Tag("database")
class ApplicationRoleGrantsTest {

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.close();
        }
    }

    @Test
    @DisplayName("every platform table grants exactly the verbs its design requires")
    void grantsMatchTheDocumentedIntent() throws SQLException {
        // Read from the catalogue rather than restated from the migration, so this fails if a
        // grant is changed anywhere - a later migration, or by hand in an environment.
        assertThat(privilegesOn("idempotency_record"))
                .as("a claim is inserted, updated with its outcome, and swept when it expires")
                .containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");

        assertThat(privilegesOn("outbox_event"))
                .as("a row is written, marked published or failed by the relay, and swept")
                .containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");

        assertThat(privilegesOn("inbox_message"))
                .as("no UPDATE: an inbox record has no state to advance (V007)")
                .containsExactly("DELETE", "INSERT", "SELECT");

        assertThat(privilegesOn("audit_record"))
                .as("INV-HIST-03: append and read, and nothing else, ever")
                .containsExactly("INSERT", "SELECT");
    }

    @Test
    @DisplayName("the inbox cannot be updated, which distinguishes its grant from the outbox's")
    void theInboxIsNotUpdatable() throws SQLException {
        // The narrow grant V007 promised, proven rather than described. Without this the
        // difference between inbox_message and outbox_event is a sentence in a comment.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () -> execute("UPDATE platform.inbox_message SET message_type = 'tampered'"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the application role holds no DDL and cannot reach the migration history")
    void theRoleIsConfinedToData() throws SQLException {
        // Flyway's history table is the record of what was applied. An application role that
        // could write it could make the schema's provenance disagree with the schema.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute("SELECT * FROM platform.flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute("CREATE TABLE platform.probe (id INT)"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the role can still read the schema it is confined to")
    void usageOnTheSchemaIsGranted() {
        // The positive control for the denials above. Without USAGE the role cannot see the
        // schema at all, and every denial in this class would pass for the wrong reason.
        assertThatCode(() -> execute("SELECT count(*) FROM platform.audit_record"))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------

    private static void execute(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> privilegesOn(String table) throws SQLException {
        try (Statement statement = application.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT privilege_type FROM information_schema.table_privileges "
                                        + "WHERE table_schema = 'platform' AND table_name = '" + table
                                        + "' AND grantee = current_user ORDER BY privilege_type")) {
            List<String> privileges = new ArrayList<>();
            while (rows.next()) {
                privileges.add(rows.getString(1));
            }
            return privileges;
        } finally {
            application.commit();
        }
    }
}
