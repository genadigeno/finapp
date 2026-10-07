package com.finapp.credit;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The credit schema's privilege floor and its immutable reason-code catalogue, proven
 * <strong>live</strong> (P10-TSK-001, {@code INV-CRD-02}'s catalogue element).
 *
 * <p>The {@code CrossborderMigrationTest} idiom: a floor is only a control as it actually stands in
 * the catalogue, so the database is held to it, and the denials are asserted at least as carefully
 * as the one permission - a floor too wide fails silently, forever.
 *
 * <p><strong>The catalogue is refused to every writer, each proven alone.</strong> The application
 * role is refused by privilege (it holds {@code SELECT} only); the owner, which holds every
 * privilege on its own table, is refused by the triggers - so a grant widened later, by migration
 * or by hand, still changes nothing. Both are exercised by raw SQL, not through any code path.
 */
@Tag("database")
@DisplayName("the credit schema floor and the reason-code catalogue are live (P10-TSK-001)")
class CreditMigrationTest {

    private static final String SCHEMA = "credit";

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** PL/pgSQL's {@code RAISE EXCEPTION} default SQLState - the immutability trigger's refusal. */
    private static final String RAISE_EXCEPTION = "P0001";

    private static Connection migrator;
    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        migrator = DatabaseRoles.migrator();
        migrator.setAutoCommit(false);
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @AfterEach
    void rollBack() throws SQLException {
        // Every refused write is attempted inside a transaction rolled back here - so even if a
        // control were missing, the shared catalogue would be left as seeded.
        migrator.rollback();
        application.rollback();
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
        // The WHOLE list, from pg_namespace: an extra grantee is invisible to a per-role probe.
        assertThat(column("SELECT unnest(nspacl)::text FROM pg_namespace WHERE nspname = '" + SCHEMA + "'"))
                .containsExactlyInAnyOrder("finapp_migrator=UC/finapp_migrator", "finapp_app=U/finapp_migrator");
    }

    @Test
    @DisplayName("the schema is owned by the migrator, never the superuser")
    void theMigratorOwnsTheSchema() throws SQLException {
        assertThat(column("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '" + SCHEMA + "'"))
                .containsExactly("finapp_migrator");
        assertThat(column("SELECT tableowner FROM pg_tables WHERE schemaname = '" + SCHEMA
                        + "' AND tablename = 'reason_code'"))
                .containsExactly("finapp_migrator");
    }

    @Test
    @DisplayName("no default privileges exist - every future table's grants must be explicit")
    void noDefaultPrivileges() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM pg_default_acl d JOIN pg_namespace n"
                        + " ON d.defaclnamespace = n.oid WHERE n.nspname = '" + SCHEMA + "'"))
                .containsExactly("0");
        // The statements, not the commentary - V001's comment NAMES the deliberate absence.
        assertThat(statementsOf(migration("V001__initialise_credit_schema.sql")))
                .doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("the schema holds Flyway's history - which the application cannot read - the reason-code"
            + " catalogue and (since V003, P10-TSK-004) the credit profile, nothing else")
    void theTablesAndTheConfinedHistory() throws SQLException {
        assertThat(column("SELECT tablename FROM pg_tables WHERE schemaname = '" + SCHEMA + "'"))
                .containsExactlyInAnyOrder("flyway_schema_history", "reason_code", "credit_profile");
        assertRefusedByPrivilege("SELECT * FROM credit.flyway_schema_history");
    }

    @Test
    @DisplayName("the application role can see the schema and create nothing in it")
    void usageWithoutCreate() throws SQLException {
        assertThatCode(() -> asApplication("SELECT to_regnamespace('" + SCHEMA + "')")).doesNotThrowAnyException();
        application.rollback();
        assertRefusedByPrivilege("CREATE TABLE credit.probe (id INT)");
    }

    @Test
    @DisplayName("the application holds SELECT on the catalogue and nothing more")
    void theCatalogueIsSelectOnly() throws SQLException {
        assertThat(column("SELECT privilege_type FROM information_schema.role_table_grants"
                        + " WHERE grantee = 'finapp_app' AND table_schema = 'credit' AND table_name = 'reason_code'"))
                .containsExactly("SELECT");
        // The positive control first: without it every denial below would pass for the wrong reason.
        assertThatCode(() -> asApplication("SELECT count(*) FROM credit.reason_code")).doesNotThrowAnyException();
        application.rollback();
    }

    @Test
    @DisplayName("the application cannot insert, update, delete or truncate a reason code - refused by privilege")
    void theApplicationIsRefusedByPrivilege() throws SQLException {
        assertRefusedByPrivilege("INSERT INTO credit.reason_code VALUES ('CRD-PROBE', 'DATA', 'probe', TRUE)");
        assertRefusedByPrivilege("UPDATE credit.reason_code SET customer_text = 'changed'"
                + " WHERE code = 'CRD-SOURCE-UNAVAILABLE'");
        assertRefusedByPrivilege("DELETE FROM credit.reason_code WHERE code = 'CRD-SOURCE-UNAVAILABLE'");
        assertRefusedByPrivilege("TRUNCATE credit.reason_code");
    }

    @Test
    @DisplayName("the owner cannot update, delete or truncate a reason code either - refused by the trigger, for"
            + " every role")
    void theOwnerIsRefusedByTheTrigger() throws SQLException {
        assertRefusedByTrigger("UPDATE credit.reason_code SET customer_text = 'changed'"
                + " WHERE code = 'CRD-SOURCE-UNAVAILABLE'");
        assertRefusedByTrigger("UPDATE credit.reason_code SET adverse = NOT adverse"
                + " WHERE code = 'CRD-AUTO-APPROVAL-CEILING'");
        assertRefusedByTrigger("DELETE FROM credit.reason_code WHERE code = 'CRD-SOURCE-UNAVAILABLE'");
        assertRefusedByTrigger("TRUNCATE credit.reason_code");
    }

    @Test
    @DisplayName("the live rows are the enum, both ways - code, category, customer text and adverse flag")
    void theLiveRowsAreTheEnum() throws SQLException {
        Set<ReasonCodeCatalogueTest.Row> live = new LinkedHashSet<>();
        try (Statement statement = application.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT code, category, customer_text, adverse FROM credit.reason_code")) {
            while (rows.next()) {
                live.add(new ReasonCodeCatalogueTest.Row(
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4)));
            }
        }
        assertThat(live).hasSize(ReasonCode.values().length);
        assertThat(ReasonCodeCatalogueTest.disagreements(ReasonCodeCatalogueTest.enumRows(), live)).isEmpty();
    }

    @Test
    @DisplayName("the CHECKs refuse a malformed code, an unknown category and a blank text - even for the owner")
    void theShapeIsChecked() throws SQLException {
        for (String insert : List.of(
                "INSERT INTO credit.reason_code VALUES ('crd-lower', 'DATA', 'text', TRUE)",
                "INSERT INTO credit.reason_code VALUES ('CRD-PROBE', 'UNKNOWN', 'text', TRUE)",
                "INSERT INTO credit.reason_code VALUES ('CRD-PROBE', 'DATA', '   ', TRUE)")) {
            assertThatExceptionOfType(SQLException.class)
                    .as(insert)
                    .isThrownBy(() -> asMigrator(insert))
                    .matches(e -> "23514".equals(e.getSQLState()), "a CHECK violation");
            migrator.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static void assertRefusedByPrivilege(String sql) throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .as(sql)
                .isThrownBy(() -> asApplication(sql))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()), "a refused privilege");
        application.rollback();
    }

    private static void assertRefusedByTrigger(String sql) throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .as(sql)
                .isThrownBy(() -> asMigrator(sql))
                .matches(e -> RAISE_EXCEPTION.equals(e.getSQLState())
                                && e.getMessage().contains("never updated or deleted"),
                        "the immutability trigger's refusal");
        migrator.rollback();
    }

    private static void asApplication(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void asMigrator(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> column(String sql) throws SQLException {
        try (Statement statement = migrator.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statementsOf(String migration) {
        return migration.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    private static String migration(String file) {
        String resource = "db/migration/credit/" + file;
        try (InputStream stream = CreditMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
