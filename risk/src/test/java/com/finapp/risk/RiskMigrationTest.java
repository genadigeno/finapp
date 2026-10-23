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
    @DisplayName("the schema holds Flyway's history - which the application cannot read - and exactly the tables and"
            + " functions its migrations created: V001 the floor alone, V002 the policy versions (P13-TSK-002)")
    void floorsOnlyAndTheHistoryIsConfined() throws SQLException {
        // Flyway keeps this schema's history HERE, which is the record of what was applied. An application role that
        // could read or write it could make the schema's provenance disagree with the schema. Each later task rewrites
        // this list with its own tables, views and functions.
        assertThat(tablesIn(SCHEMA)).containsExactly(
                "flyway_schema_history",
                // V002 (P13-TSK-002): the machine row, the content, the rules and their parameters, the bands, the history.
                "risk_policy",
                "risk_policy_band",
                "risk_policy_version",
                "risk_policy_version_event",
                "risk_rule",
                "risk_rule_parameter");
        assertThat(column("SELECT viewname FROM pg_views WHERE schemaname = '" + SCHEMA + "'")).isEmpty();
        assertThat(column("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' ORDER BY p.proname")).containsExactly(
                // V002 (P13-TSK-002): the reason screen's twin and the policy machine's triggers.
                "holds_account_identifier_shape",
                "holds_card_number_shape",
                "holds_instrument_shape",
                "luhn_valid",
                "mod97_check_holds",
                "risk_policy_content_is_born_once",
                "risk_policy_is_never_truncated",
                "risk_policy_version_event_is_append_only",
                "risk_policy_version_has_its_content",
                "risk_policy_version_permits_only_machine_edges",
                "risk_policy_version_retires_only_beside_its_successor");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("SELECT * FROM " + SCHEMA + ".flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
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
