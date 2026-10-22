package com.finapp.risk;

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
 * The risk schema's privilege floor, proven <strong>live</strong> (`P13-TSK-001`) - {@code BnplMigrationTest}'s idiom,
 * as every schema-owning module has its own.
 *
 * <p>The migration-test idiom reads the SQL and holds it to the code; this class holds the <em>database</em> to it,
 * because a floor is only a control as it actually stands in the catalogue - a grant added by a later migration or by
 * hand in an environment never edits the file this task shipped. The {@code ApplicationRoleGrantsTest} asymmetry
 * applies: a floor too narrow fails loudly at the first feature; a floor too wide fails silently, forever - so the
 * denials below are asserted at least as carefully as the one permission.
 *
 * <p>Why the floor matters before the first table: every table {@code PHASE_13_PLAN.md} section 8 commits to this schema
 * arrives with its own narrowed grants - INSERT-only facts, a column-scoped UPDATE on the machine tables, the velocity
 * window's bounded upsert, no DELETE for any role - and that is only reviewable per table because nothing arrives by
 * default ({@code INV-RSK-01}, {@code INV-RSK-05}, {@code INV-RSK-06}).
 */
@Tag("database")
@DisplayName("the risk schema floor is live (P13-TSK-001)")
class RiskMigrationTest {

    private static final String SCHEMA = "risk";

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
    @DisplayName("the ACL is exactly {finapp_migrator=UC, finapp_app=U} - no PUBLIC, no one else")
    void theAclIsExactlyTheFloor() throws SQLException {
        // Read from pg_namespace rather than via has_schema_privilege, because the acceptance criterion is the WHOLE
        // list: an extra grantee is invisible to a per-role probe, and an extra grantee is precisely the silent failure
        // a floor exists to prevent.
        assertThat(aclOf(SCHEMA))
                .containsExactlyInAnyOrder("finapp_migrator=UC/finapp_migrator", "finapp_app=U/finapp_migrator");
    }

    @Test
    @DisplayName("the schema is owned by the migrator, never the superuser")
    void theMigratorOwnsTheSchema() throws SQLException {
        // P0-TSK-022: the floor is only a control because the objects are owned by a role that cannot bypass the grants
        // it applies.
        assertThat(scalar("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '" + SCHEMA + "'"))
                .isEqualTo("finapp_migrator");
    }

    @Test
    @DisplayName("no default privileges exist - every future table's grants must be explicit")
    void noDefaultPrivileges() throws SQLException {
        // The deliberate absence the migration records: a default UPDATE or DELETE here would make every sealed
        // assessment, born-once alert and immutable policy version editable the moment its table was created.
        assertThat(scalar("SELECT count(*)::text FROM pg_default_acl d"
                        + " JOIN pg_namespace n ON d.defaclnamespace = n.oid"
                        + " WHERE n.nspname = '" + SCHEMA + "'"))
                .isEqualTo("0");
        // The statements, not the commentary - the migration's comment NAMES the deliberate absence, so the text check
        // reads only what executes.
        assertThat(statementsOf(migration())).doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("the schema holds Flyway's history - which the application cannot read - and exactly the tables the"
            + " landed tasks created: V003's risk_signal (P13-TSK-005)")
    void floorsOnlyAndTheHistoryIsConfined() throws SQLException {
        // Flyway keeps this schema's history HERE, which is the record of what was applied. An application role that
        // could read or write it could make the schema's provenance disagree with the schema. Each later task rewrites
        // this list with its own tables, views and functions.
        assertThat(tablesIn(SCHEMA)).containsExactly("flyway_schema_history", "risk_signal");
        assertThat(column("SELECT viewname FROM pg_views WHERE schemaname = '" + SCHEMA + "'")).isEmpty();
        assertThat(column("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' ORDER BY p.proname"))
                .containsExactly("risk_signal_is_born_once", "risk_signal_is_never_edited");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("SELECT * FROM " + SCHEMA + ".flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("risk_signal (V003): the application holds SELECT and INSERT and nothing else; no role holds UPDATE, DELETE"
            + " or TRUNCATE, and the trigger refuses the migrator's edit too (P13-TSK-005, INV-RSK-06)")
    void theSignalTableIsInsertOnlyForEveryWriter() throws SQLException {
        // The whole ACL, as for the schema: an extra grantee or an extra privilege is the silent failure a floor exists
        // to prevent, invisible to a per-role probe.
        assertThat(column("SELECT grantee || '=' || string_agg(privilege_type, ',' ORDER BY privilege_type)"
                + " FROM information_schema.role_table_grants"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'risk_signal'"
                + " GROUP BY grantee ORDER BY grantee"))
                .containsExactlyInAnyOrder(
                        "finapp_app=INSERT,SELECT",
                        // The owner's full set as PostgreSQL 18 (the catalogue's image) reports it - MAINTAIN since 17.
                        "finapp_migrator=DELETE,INSERT,MAINTAIN,REFERENCES,SELECT,TRIGGER,TRUNCATE,UPDATE");

        // The application role: refused by privilege before any trigger runs.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("UPDATE " + SCHEMA + ".risk_signal SET kind = kind"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM " + SCHEMA + ".risk_signal"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        // The owner: holds the privilege and is refused by the trigger - a signal is evidence for EVERY writer. The
        // row is planted as the migrator, in a transaction rolled back after the probe.
        migrator.setAutoCommit(false);
        try (Statement plant = migrator.createStatement()) {
            plant.execute("INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref,"
                    + " source_event_id, source_event_type, source_event_version, producer, observed_at,"
                    + " correlation_id, causation_id)"
                    + " VALUES (gen_random_uuid(), 'CREDENTIAL_CHANGED', 'IDENTITY', gen_random_uuid()::text,"
                    + " gen_random_uuid(), 'identity.CredentialChanged', 1, 'identity', now(), 'flow', 'cause')");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> plant.execute("UPDATE " + SCHEMA + ".risk_signal SET producer = 'x'"))
                    .withMessageContaining("never updated or deleted");
            migrator.rollback();
            plant.execute("INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref,"
                    + " source_event_id, source_event_type, source_event_version, producer, observed_at,"
                    + " correlation_id, causation_id)"
                    + " VALUES (gen_random_uuid(), 'CREDENTIAL_CHANGED', 'IDENTITY', gen_random_uuid()::text,"
                    + " gen_random_uuid(), 'identity.CredentialChanged', 1, 'identity', now(), 'flow', 'cause')");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> plant.execute("DELETE FROM " + SCHEMA + ".risk_signal"))
                    .withMessageContaining("never updated or deleted");
            migrator.rollback();
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> plant.execute("TRUNCATE " + SCHEMA + ".risk_signal"))
                    .withMessageContaining("never updated or deleted");
            migrator.rollback();
        } finally {
            migrator.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("every constraint name in the schema is unique per table, and the CHECKs refuse a description as a device"
            + " and an attribute its kind does not carry (the V012 class; OQ-4)")
    void theConstraintsAreNamedOnceAndLive() throws SQLException {
        assertThat(column("SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' GROUP BY conname HAVING count(*) > 1"))
                .as("a constraint name used twice on one table is refused by PostgreSQL at the SECOND migration that"
                        + " uses it (23505) - the Phase 12 V012 class")
                .isEmpty();

        String row = "INSERT INTO " + SCHEMA + ".risk_signal (id, kind, subject_kind, subject_ref, source_event_id,"
                + " source_event_type, source_event_version, producer, observed_at, correlation_id, causation_id,"
                + " product) VALUES (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), ?, 1, 'x', now(), 'flow', 'cause', ?)";
        // A device description as a subject reference: refused by the shape CHECK, whatever the writer.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication(row, "ACCOUNT_OPENED", "DEVICE", "Chrome on Windows",
                        "accounts.AccountOpened", "WALLET"))
                .matches(e -> "23514".equals(e.getSQLState()));
        application.rollback();
        // An attribute a kind does not carry: CREDENTIAL_CHANGED carries none.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication(row, "CREDENTIAL_CHANGED", "IDENTITY",
                        java.util.UUID.randomUUID().toString(), "identity.CredentialChanged", "WALLET"))
                .matches(e -> "23514".equals(e.getSQLState()));
        application.rollback();
        // The positive control: the shape admits what the kind declares.
        assertThatCode(() -> asApplication(row, "ACCOUNT_OPENED", "ACCOUNT", java.util.UUID.randomUUID().toString(),
                "accounts.AccountOpened", "WALLET"))
                .doesNotThrowAnyException();
        application.rollback();
    }

    @Test
    @DisplayName("the application role can see the schema and create nothing in it")
    void usageWithoutCreate() throws SQLException {
        // The positive control first: without USAGE every denial below would pass for the wrong reason, because the
        // role could not name the schema at all.
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

    private static void asApplication(String sql, Object... arguments) throws SQLException {
        try (java.sql.PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.execute();
        }
    }

    private static List<String> aclOf(String schema) throws SQLException {
        return column("SELECT unnest(nspacl)::text FROM pg_namespace WHERE nspname = '" + schema + "'");
    }

    private static List<String> tablesIn(String schema) throws SQLException {
        return column("SELECT tablename FROM pg_tables WHERE schemaname = '" + schema + "' ORDER BY tablename");
    }

    private static List<String> column(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
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
        String resource = "db/migration/risk/V001__initialise_risk_schema.sql";
        try (InputStream stream = RiskMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
