package com.finapp.settlement;

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
 * The settlement schema's privilege floor, proven <strong>live</strong> (P8-TSK-001).
 *
 * <p>The migration-test idiom ({@code ConsentMigrationTest}) reads the SQL and holds it to the
 * code; this class holds the <em>database</em> to it, because a floor is only a control as it
 * actually stands in the catalogue — a grant added by a later migration or by hand in an
 * environment never edits the file this task shipped. The {@code ApplicationRoleGrantsTest}
 * asymmetry applies: a floor too narrow fails loudly at the first feature; a floor too wide
 * fails silently, forever — so the denials below are asserted at least as carefully as the one
 * permission.
 *
 * <p>Why the floor matters before the first table: every evidence table PHASE_8_PLAN.md §8
 * commits to append-only grants — {@code finapp_app} holding no {@code DELETE} anywhere in this
 * schema, ever — is only reviewable per table because nothing arrives by default.
 */
@Tag("database")
@DisplayName("the settlement schema floor is live (P8-TSK-001)")
class SettlementMigrationTest {

    private static final String SCHEMA = "settlement";

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
        // hand every future evidence table a uniform grant set, and the tables this schema will
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
    @DisplayName("the schema holds exactly the tables its migrations created, and the"
            + " application cannot read Flyway's history")
    void tablesAreExactlyTheMigrationsAndTheHistoryIsConfined() throws SQLException {
        // The P8-TSK-001 floor asserted "no table but Flyway's history"; each migration widens
        // this list with exactly what it creates, so a table nobody's task shipped is a
        // failure here. The history stays the migrator's: an application role that could
        // write it could make the schema's provenance disagree with the schema.
        assertThat(tablesIn(SCHEMA))
                .containsExactly(
                        "batch",
                        "batch_event",
                        "batch_total",
                        "file",
                        "file_chunk",
                        "file_event",
                        "file_receipt",
                        "flyway_schema_history",
                        "ingestion_error",
                        "line",
                        "line_reference",
                        "pull_permit",
                        "refused_delivery",
                        "source");

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

    @Test
    @DisplayName("the generated fragments are each enum's mirror in their LATEST defining"
            + " migration - one definition, two artefacts (the V014 pattern)")
    void generatedFragmentsMatchTheEnums() {
        String v003 = collapsed(migration(
                "db/migration/settlement/V003__the_psp_format_parse_normalise_reject_whole.sql"));
        String v004 = collapsed(migration(
                "db/migration/settlement/V004__acceptance_joins_the_machines.sql"));
        String v010 = collapsed(migration(
                "db/migration/settlement/V010__the_repudiated_batch.sql"));

        // The file machine and the rejection codes were re-stated by V004 (ACCEPTED and
        // SOURCE_RETIRED arrived with their producers) - the current definitions live there.
        assertThat(v004).contains("status IN (" + FileStatus.sqlValueList() + ")");
        assertThat(v004).contains(FileStatus.sqlTransitionRule());
        // The rejection codes were re-stated whole by V015 (CURRENCY_NOT_SETTLED arrived with
        // P9-TSK-011) - SettlementV015MigrationTest holds the current list; V004's is history.
        assertThat(v004).contains("rejection_code IN (" + RejectionCode.sqlValueList(
                java.util.EnumSet.range(RejectionCode.MALFORMED, RejectionCode.SOURCE_RETIRED)) + ")");
        // The batch machine was re-stated whole by V010 (REPUDIATED arrived with P8-TSK-023).
        assertThat(v010).contains("status IN (" + BatchStatus.sqlValueList() + ")");
        assertThat(v010).contains(BatchStatus.sqlTransitionRule());

        // V003 keeps the current definitions it introduced: line types (twice - totals and
        // lines), directions, reference kinds, the error-row codes, and the live unique -
        // written WHOLE, so neither V004 nor P8-TSK-023 changes the index.
        assertThat(v003).contains("line_type IN ("
                + SettlementLineType.sqlValueList(SettlementLineType.reportVocabulary()) + ")");
        assertThat(v003).contains("direction IN (" + LineDirection.sqlValueList() + ")");
        assertThat(v003).contains("kind IN ("
                + LineReferenceKind.sqlValueList(LineReferenceKind.reportVocabulary()) + ")");
        assertThat(v003).contains("error_code IN (" + RejectionCode.sqlErrorRowList() + ")");
        assertThat(v003)
                .contains("ON settlement.batch (source_id, external_batch_ref, currency)")
                .contains("WHERE status NOT IN ('REJECTED', 'REPUDIATED')");

        // V004's own arbiter; its honesty rules, widened by V010 to the repudiated row - a
        // repudiated batch WAS accepted, and keeps what its acceptance recorded.
        assertThat(v004)
                .contains("CONSTRAINT batch_sequence_once UNIQUE (source_id, source_sequence)");
        assertThat(v010)
                .contains(normalizedFragment(
                        "CONSTRAINT batch_accepted_carries_its_facts CHECK ("
                                + " status NOT IN ('ACCEPTED', 'REPUDIATED')"
                                + " OR (source_sequence IS NOT NULL AND"
                                + " accepted_on IS NOT NULL))"))
                .contains(normalizedFragment(
                        "CONSTRAINT batch_posting_omitted_is_honest CHECK ("
                                + " status NOT IN ('ACCEPTED', 'REPUDIATED')"
                                + " OR ((journal_entry_id IS NULL) = posting_omitted))"));
    }

    private static String normalizedFragment(String fragment) {
        return fragment.replaceAll("\\s+", " ").replaceAll("\\( ", "(");
    }

    /** Whitespace collapsed, so a generated fragment matches however the SQL wraps. */
    private static String collapsed(String migration) {
        return statementsOf(migration).replaceAll("\\s+", " ").replaceAll("\\( ", "(");
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
        return migration("db/migration/settlement/V001__initialise_settlement_schema.sql");
    }

    private static String migration(String resource) {
        try (InputStream stream =
                SettlementMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
