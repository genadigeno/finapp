package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * The reconciliation schema's privilege floor, proven <strong>live</strong> (P8-TSK-001).
 *
 * <p>The migration-test idiom ({@code ConsentMigrationTest}) reads the SQL and holds it to the
 * code; this class holds the <em>database</em> to it, because a floor is only a control as it
 * actually stands in the catalogue — a grant added by a later migration or by hand in an
 * environment never edits the file this task shipped. The {@code ApplicationRoleGrantsTest}
 * asymmetry applies: a floor too narrow fails loudly at the first feature; a floor too wide
 * fails silently, forever — so the denials below are asserted at least as carefully as the one
 * permission.
 *
 * <p>Why the floor matters before the first table: "no code path deletes or overwrites a
 * break" is a Phase 8 exit criterion, and it is a floor rather than a promise only because
 * {@code finapp_app} holds no {@code DELETE} anywhere in this schema, ever — reviewable per
 * table because nothing arrives by default.
 */
@Tag("database")
@DisplayName("the reconciliation schema floor is live (P8-TSK-001)")
class ReconciliationMigrationTest {

    private static final String SCHEMA = "reconciliation";

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static Connection migrator;
    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        migrator = DatabaseRoles.migrator();
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (migrator != null) {
            migrator.close();
        }
        if (application != null) {
            application.close();
        }
    }

    @Test
    @DisplayName("the ACL is exactly {finapp_migrator=UC, finapp_app=U} — no PUBLIC, no one else")
    void theAclIsExactlyTheFloor() throws SQLException {
        // Read from pg_namespace rather than via has_schema_privilege, because the acceptance
        // criterion is the WHOLE list: an extra grantee is invisible to a per-role probe, and an
        // extra grantee is precisely the silent failure a floor exists to prevent.
        assertThat(aclOf(SCHEMA))
                .containsExactlyInAnyOrder(
                        "finapp_migrator=UC/finapp_migrator", "finapp_app=U/finapp_migrator");
    }

    @Test
    @DisplayName("the schema is owned by the migrator, never the superuser")
    void theMigratorOwnsTheSchema() throws SQLException {
        // P0-TSK-022: the floor is only a control because the objects are owned by a role that
        // cannot bypass the grants it applies.
        assertThat(scalar("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '"
                        + SCHEMA + "'"))
                .isEqualTo("finapp_migrator");
    }

    @Test
    @DisplayName("no default privileges exist — every future table's grants must be explicit")
    void noDefaultPrivileges() throws SQLException {
        // The deliberate absence the migration records: an ALTER DEFAULT PRIVILEGES here would
        // hand every future break or decision table a uniform grant set, and the tables this schema will
        // hold are exactly ones whose grants must be narrowed per table.
        assertThat(scalar("SELECT count(*)::text FROM pg_default_acl d"
                        + " JOIN pg_namespace n ON d.defaclnamespace = n.oid"
                        + " WHERE n.nspname = '" + SCHEMA + "'"))
                .isEqualTo("0");
        // The statements, not the commentary - the migration's comment NAMES the deliberate
        // absence, so the text check reads only what executes.
        assertThat(statementsOf(migration())).doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("the schema holds no table but Flyway's history, which the application cannot read")
    void floorsOnlyAndTheHistoryIsConfined() throws SQLException {
        // "No tables" with one honest exception: Flyway keeps this schema's history HERE, which
        // is the record of what was applied. An application role that could read or write it
        // could make the schema's provenance disagree with the schema.
        assertThat(tablesIn(SCHEMA)).containsExactly("flyway_schema_history");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication(
                        "SELECT * FROM " + SCHEMA + ".flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the application role can see the schema and create nothing in it")
    void usageWithoutCreate() throws SQLException {
        // The positive control first: without USAGE every denial below would pass for the wrong
        // reason, because the role could not name the schema at all.
        assertThatCode(() -> asApplication("SELECT to_regnamespace('" + SCHEMA + "')"))
                .doesNotThrowAnyException();
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("CREATE TABLE " + SCHEMA + ".probe (id INT)"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    // -----------------------------------------------------------------

    private static void asApplication(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> aclOf(String schema) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT unnest(nspacl)::text FROM pg_namespace WHERE nspname = '"
                                        + schema + "'")) {
            List<String> entries = new ArrayList<>();
            while (rows.next()) {
                entries.add(rows.getString(1));
            }
            return entries;
        }
    }

    private static List<String> tablesIn(String schema) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT tablename FROM pg_tables WHERE schemaname = '" + schema
                                        + "' ORDER BY tablename")) {
            List<String> tables = new ArrayList<>();
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
            return tables;
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("one row from: %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statementsOf(String migration) {
        return migration.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        String resource = "db/migration/reconciliation/V001__initialise_reconciliation_schema.sql";
        try (InputStream stream =
                ReconciliationMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
