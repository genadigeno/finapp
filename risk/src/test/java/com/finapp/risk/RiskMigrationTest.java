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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The risk schema's privilege floor and its first tables, proven <strong>live</strong> (`P13-TSK-001`; `P13-TSK-019`
 * adds {@code V004}: {@code monitored_transaction}, {@code monitoring_watermark}) - {@code BnplMigrationTest}'s idiom,
 * as every schema-owning module has its own.
 *
 * <p>The migration-test idiom reads the SQL and holds it to the code; this class holds the <em>database</em> to it,
 * because a floor is only a control as it actually stands in the catalogue - a grant added by a later migration or by
 * hand in an environment never edits the file this task shipped. The {@code ApplicationRoleGrantsTest} asymmetry
 * applies: a floor too narrow fails loudly at the first feature; a floor too wide fails silently, forever - so the
 * denials below are asserted at least as carefully as the one permission.
 *
 * <p>Each task rewrites the table, view and function lists with its own objects, keeping the others' (the wave's
 * rule): `-002` adds the policy tables, `-005` the signal table; this file names what `-001` and `-019` created.
 */
@Tag("database")
@DisplayName("the risk schema floor and the monitoring tables are live (P13-TSK-001, P13-TSK-019)")
class RiskMigrationTest {

    private static final String SCHEMA = "risk";

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** PostgreSQL's SQLState for a trigger's or CHECK's refusal. */
    private static final String CHECK_VIOLATION = "23514";

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
        assertThat(aclOf(SCHEMA))
                .containsExactlyInAnyOrder("finapp_migrator=UC/finapp_migrator", "finapp_app=U/finapp_migrator");
    }

    @Test
    @DisplayName("the schema is owned by the migrator, never the superuser")
    void theMigratorOwnsTheSchema() throws SQLException {
        assertThat(scalar("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '" + SCHEMA + "'"))
                .isEqualTo("finapp_migrator");
    }

    @Test
    @DisplayName("no default privileges exist - every table's grants are explicit")
    void noDefaultPrivileges() throws SQLException {
        assertThat(scalar("SELECT count(*)::text FROM pg_default_acl d"
                        + " JOIN pg_namespace n ON d.defaclnamespace = n.oid"
                        + " WHERE n.nspname = '" + SCHEMA + "'"))
                .isEqualTo("0");
        assertThat(statementsOf(migration("V001__initialise_risk_schema.sql"))).doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("the schema holds Flyway's history - which the application cannot read - and the tables and functions"
            + " the tasks so far created, exactly")
    void theObjectsAreExactlyTheTasksSoFar() throws SQLException {
        // Each later task rewrites these lists with its own tables, views and functions, keeping the others'.
        assertThat(tablesIn(SCHEMA)).containsExactly("flyway_schema_history", "monitored_transaction",
                "monitoring_watermark");
        assertThat(column("SELECT viewname FROM pg_views WHERE schemaname = '" + SCHEMA + "'")).isEmpty();
        assertThat(column("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '" + SCHEMA + "' ORDER BY p.proname")).containsExactly(
                "monitored_transaction_is_append_only", "monitored_transaction_is_stamped_by_the_database",
                "monitoring_watermark_derives_its_sequence", "uuid7_sequence");

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("SELECT * FROM " + SCHEMA + ".flyway_schema_history"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("the application role can see the schema and create nothing in it")
    void usageWithoutCreate() throws SQLException {
        assertThatCode(() -> asApplication("SELECT to_regnamespace('" + SCHEMA + "')"))
                .doesNotThrowAnyException();
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("CREATE TABLE " + SCHEMA + ".probe (id INT)"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    // ------------------------------------------------------------------ V004 (P13-TSK-019)

    @Test
    @DisplayName("V004: the grants - the fact table SELECT and INSERT only; the watermark SELECT and an UPDATE of its"
            + " position and permit alone; no INSERT of a source, no DELETE anywhere")
    void theMonitoringGrants() throws SQLException {
        assertThat(grantsOn("monitored_transaction")).containsExactlyInAnyOrder("finapp_app:INSERT", "finapp_app:SELECT");
        assertThat(grantsOn("monitoring_watermark")).containsExactlyInAnyOrder("finapp_app:SELECT");
        assertThat(column("SELECT column_name FROM information_schema.column_privileges"
                + " WHERE table_schema = 'risk' AND table_name = 'monitoring_watermark' AND grantee = 'finapp_app'"
                + " AND privilege_type = 'UPDATE' ORDER BY column_name"))
                .containsExactly("last_entry_id", "next_sweep_at");
        // The statements, not the commentary.
        String statements = statementsOf(migration("V004__monitored_transactions_and_the_watermark.sql"));
        assertThat(statements).doesNotContain("DELETE ON").doesNotContain("GRANT ALL");
    }

    @Test
    @DisplayName("V004: the monetary shape is MoneyColumns.ColumnNames.ddl()'s verbatim, and the kind CHECK is exactly"
            + " MonitoredKind's members")
    void theShapesAreTheCodes() throws SQLException {
        String migration = migration("V004__monitored_transactions_and_the_watermark.sql");
        assertThat(migration).contains("amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL,"
                + " CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),");
        String kinds = scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = 'monitored_transaction_kind_is_known'");
        for (MonitoredKind kind : MonitoredKind.values()) {
            assertThat(kinds).as(kind.name()).contains("'" + kind.name() + "'::text");
        }
        assertThat(kinds.split("::text").length - 1).as("no kind the enum does not declare")
                .isEqualTo(MonitoredKind.values().length);
        for (MonitoredEntry.EntryType type : MonitoredEntry.EntryType.values()) {
            assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conname = 'monitored_transaction_entry_type_is_known'")).contains("'" + type.name() + "'");
        }
        for (MonitoredCounterpartyKind kind : MonitoredCounterpartyKind.values()) {
            assertThat(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conname = 'monitored_transaction_counterparty_kind_is_known'"))
                    .contains("'" + kind.name() + "'");
        }
    }

    @Test
    @DisplayName("V004: every constraint name is unique in the schema (the Phase 12 V012 class)")
    void constraintNamesAreUnique() throws SQLException {
        assertThat(column("SELECT conname FROM pg_constraint WHERE connamespace = 'risk'::regnamespace"
                + " GROUP BY conname HAVING count(*) > 1")).isEmpty();
    }

    @Test
    @DisplayName("V004: the one watermark row is seeded for the ledger journal with its sequence derived by the database"
            + " from its position; the application cannot write the sequence, insert a source or delete the row")
    void theWatermarkIsSeededAndDerived() throws SQLException {
        // The one row, whatever position the intake suites have moved it to in this database - and its sequence
        // always the database's derivation of that position (the seed is the floor, sequence 0).
        assertThat(column("SELECT source FROM risk.monitoring_watermark")).containsExactly("ledger.journal");
        assertThat(scalar("SELECT (last_sequence = risk.uuid7_sequence(last_entry_id))::text"
                + " FROM risk.monitoring_watermark WHERE source = 'ledger.journal'")).isEqualTo("true");
        assertThat(statementsOf(migration("V004__monitored_transactions_and_the_watermark.sql")))
                .contains("VALUES ('ledger.journal', '00000000-0000-7000-8000-000000000000', 0, clock_timestamp(),"
                        + " clock_timestamp())");
        assertThat(scalar("SELECT risk.uuid7_sequence('0199a3c1-2b3c-7d4e-8f00-000000000000'::uuid)::text"))
                .isEqualTo(Long.toString(0x0199a3c12b3cL));
        assertThat(scalar("SELECT risk.uuid7_sequence('00000000-0000-7000-8000-000000000000'::uuid)::text"))
                .isEqualTo("0");

        // The move the sweep makes: the position, the permit - and the sequence follows from the id by trigger.
        UUID position = MonitoringWatermark.positionAt(1_700_000_000_000L);
        try (PreparedStatement move = application.prepareStatement("UPDATE risk.monitoring_watermark"
                + " SET last_entry_id = ?, next_sweep_at = clock_timestamp() WHERE source = 'ledger.journal'")) {
            move.setObject(1, position);
            assertThat(move.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement read = application.prepareStatement(
                "SELECT last_sequence FROM risk.monitoring_watermark WHERE source = 'ledger.journal'");
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isEqualTo(1_700_000_000_000L);
        }
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("UPDATE risk.monitoring_watermark SET last_sequence = 5"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("INSERT INTO risk.monitoring_watermark"
                        + " (source, last_entry_id, last_sequence, next_sweep_at, advanced_at)"
                        + " VALUES ('ledger.journal', '00000000-0000-7000-8000-000000000000', 0, now(), now())"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM risk.monitoring_watermark"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
    }

    @Test
    @DisplayName("V004: a monitored transaction is born once - a second row for the entry is refused - and never edited,"
            + " by privilege for the application and by trigger for the migrator")
    void theMonitoredTransactionIsBornOnceAndAppendOnly() throws SQLException {
        UUID entry = UUID.randomUUID();
        insertAsApplication(UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000001"), entry);
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertAsApplication(UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000002"), entry))
                .matches(e -> "23505".equals(e.getSQLState()));
        application.rollback();

        insertAsApplication(UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000003"), entry);
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("UPDATE risk.monitored_transaction SET kind = 'TRANSFER'"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> asApplication("DELETE FROM risk.monitored_transaction"))
                .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
        application.rollback();

        // The migrator holds the privilege and meets the trigger instead.
        migrator.setAutoCommit(false);
        try {
            insertAs(migrator, UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000004"), UUID.randomUUID());
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (Statement statement = migrator.createStatement()) {
                            statement.execute("UPDATE risk.monitored_transaction SET kind = 'TRANSFER'");
                        }
                    })
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
            migrator.rollback();
            insertAs(migrator, UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000005"), UUID.randomUUID());
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> {
                        try (Statement statement = migrator.createStatement()) {
                            statement.execute("DELETE FROM risk.monitored_transaction");
                        }
                    })
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
            migrator.rollback();
        } finally {
            migrator.setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("V004: recorded_at is the database clock's whatever the insert said, and the CHECKs refuse a subject"
            + " without its reference, a direction without a subject and a reversal without its original")
    void theRowIsStampedAndWhole() throws SQLException {
        UUID id = UUID.fromString("0199a3c1-2b3c-7d4e-8f00-000000000006");
        insertAsApplication(id, UUID.randomUUID());
        try (PreparedStatement read = application.prepareStatement(
                "SELECT recorded_at > '2000-01-01'::timestamptz FROM risk.monitored_transaction WHERE id = ?")) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean(1)).as("the insert said the year 2000; the trigger said now").isTrue();
            }
        }
        application.rollback();

        for (String broken : List.of(
                "subject_kind = 'ACCOUNT'",
                "direction = 'IN'",
                "entry_type = 'REVERSAL'",
                "kind = 'SOMETHING_ELSE'",
                "amount_minor = -1")) {
            assertThatExceptionOfType(SQLException.class)
                    .as(broken)
                    .isThrownBy(() -> insertBroken(broken))
                    .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
            application.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static void insertAsApplication(UUID id, UUID entry) throws SQLException {
        insertAs(application, id, entry);
    }

    private static void insertAs(Connection as, UUID id, UUID entry) throws SQLException {
        try (PreparedStatement insert = as.prepareStatement(
                "INSERT INTO risk.monitored_transaction (id, journal_entry_id, journal_sequence, entry_type,"
                        + " posting_namespace, kind, counterparty_kind, amount_minor, currency, scale, value_date,"
                        + " posted_at, recorded_at)"
                        + " VALUES (?, ?, 1, 'POSTING', 'transfer:', 'TRANSFER', 'OPERATIONAL', 100, 'EUR', 2,"
                        + " '2026-10-23', '2026-10-23T09:00:00Z', '2000-01-01T00:00:00Z')")) {
            insert.setObject(1, id);
            insert.setObject(2, entry);
            insert.executeUpdate();
        }
    }

    /** A whole, valid row with exactly one column set to {@code broken}'s value ({@code column = value}). */
    private static void insertBroken(String broken) throws SQLException {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("id", "'0199a3c1-2b3c-7d4e-8f00-000000000008'");
        values.put("journal_entry_id", "'" + UUID.randomUUID() + "'");
        values.put("journal_sequence", "1");
        values.put("entry_type", "'POSTING'");
        values.put("posting_namespace", "'transfer:'");
        values.put("kind", "'TRANSFER'");
        values.put("subject_kind", "NULL");
        values.put("subject_ref", "NULL");
        values.put("direction", "NULL");
        values.put("counterparty_kind", "'OPERATIONAL'");
        values.put("amount_minor", "100");
        values.put("currency", "'EUR'");
        values.put("scale", "2");
        values.put("value_date", "'2026-10-23'");
        values.put("posted_at", "'2026-10-23T09:00:00Z'");
        values.put("recorded_at", "now()");
        values.put("reverses_journal_entry_id", "NULL");
        String column = broken.substring(0, broken.indexOf('=')).trim();
        String value = broken.substring(broken.indexOf('=') + 1).trim();
        if (!values.containsKey(column)) {
            throw new IllegalArgumentException(column);
        }
        values.put(column, value);
        asApplication("INSERT INTO risk.monitored_transaction (" + String.join(", ", values.keySet())
                + ") VALUES (" + String.join(", ", values.values()) + ")");
    }

    private static void asApplication(String sql) throws SQLException {
        try (Statement statement = application.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> grantsOn(String table) throws SQLException {
        return column("SELECT grantee || ':' || privilege_type FROM information_schema.table_privileges"
                + " WHERE table_schema = 'risk' AND table_name = '" + table + "' AND grantee <> 'finapp_migrator'");
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
        return Arrays.stream(migration.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration(String file) {
        String resource = "db/migration/risk/" + file;
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
