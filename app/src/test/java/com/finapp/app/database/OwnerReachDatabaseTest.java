package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.ReopenedClock;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The owner's reach over the rest of financial history, closed by {@code P15-TSK-040} (ledger {@code V035}, payments
 * {@code V039}, fx {@code V012}, crossborder {@code V007}, merchant {@code V015}, checkout {@code V005}, kyc {@code V013}, risk
 * {@code V021}, accounting {@code V016}, bnpl {@code V022}, credit {@code V024}, settlement {@code V020}; {@code THREAT_MODEL.md}
 * T13, T33). The text is held by {@code OwnerReachOverHistoryIsClosedTest}; this is the database. Owed to {@code P15-TST-002} (C14);
 * the {@code TRUNCATE} refusals, the qualified calls and this suite's insert were walked by hand on a throwaway PostgreSQL 18 as
 * the migrator and the application role when the task was built.
 */
@Tag("database")
@DisplayName("an owner cannot empty an append-only table in one statement, and a role's path moves no clock (P15-TSK-040)")
class OwnerReachDatabaseTest {

    /** The tables the task closed, by schema, as {@code OwnerReachOverHistoryIsClosedTest} named them when its list was emptied. */
    private static final Map<String, Integer> TABLES_CLOSED =
            Map.of("checkout", 1, "crossborder", 8, "fx", 19, "kyc", 1, "ledger", 4, "merchant", 6, "payments", 11, "risk", 1);

    /** At least this many functions read a clock in each schema, so a scan that saw none is not green. */
    private static final Map<String, Integer> FUNCTIONS_THAT_READ_A_CLOCK = Map.ofEntries(
            Map.entry("accounting", 18), Map.entry("bnpl", 33), Map.entry("checkout", 1), Map.entry("credit", 16),
            Map.entry("crossborder", 1), Map.entry("fx", 8), Map.entry("ledger", 1), Map.entry("merchant", 3),
            Map.entry("payments", 7), Map.entry("risk", 19), Map.entry("settlement", 1));

    private static final String SHADOW = "ownerreachclock";

    /** A comment or a string is not code: the alternation takes whichever starts first, so a clock in either is not a call. */
    private static final Pattern NOT_CODE = Pattern.compile("(?s)--[^\\n]*|/\\*.*?\\*/|'(?:[^']|'')*'");

    private static final Pattern A_CLOCK_THROUGH_THE_PATH =
            Pattern.compile("(?i)(?<![.\\w\"])(now|statement_timestamp|clock_timestamp|transaction_timestamp|timeofday)\\s*\\(");

    private static final Pattern A_QUALIFIED_CLOCK =
            Pattern.compile("(?i)pg_catalog\\.(now|statement_timestamp|clock_timestamp|transaction_timestamp)\\s*\\(");

    @BeforeAll
    static void aSchemaOfShadowClocks() throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SHADOW + "; GRANT USAGE ON SCHEMA " + SHADOW + " TO PUBLIC;");
            for (String clock : new String[] {"now", "statement_timestamp", "clock_timestamp", "transaction_timestamp"}) {
                statement.execute("CREATE OR REPLACE FUNCTION " + SHADOW + "." + clock + "() RETURNS timestamptz LANGUAGE sql STABLE AS"
                        + " $$ SELECT timestamptz '2000-01-01 00:00:00+00' $$");
            }
        }
    }

    @AfterAll
    static void noShadowLeftBehind() throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SHADOW + " CASCADE");
        }
    }

    @Test
    @DisplayName("as the migrator, TRUNCATE ... CASCADE is refused by the named table's own trigger, on all 51, the journal's two among them")
    void everyClosedTableRefusesItsOwner() throws SQLException {
        Map<String, List<String>> closed = new TreeMap<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            migrator.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("SELECT n.nspname, c.relname FROM pg_catalog.pg_trigger t"
                    + " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid"
                    + " WHERE p.proname = 'history_is_never_truncated' AND t.tgname = c.relname || '_is_never_truncated'"
                    + " AND t.tgenabled = 'O' AND n.nspname = ANY (ARRAY['checkout', 'crossborder', 'fx', 'kyc', 'ledger', 'merchant',"
                    + " 'payments', 'risk'])")) {
                while (rows.next()) {
                    closed.computeIfAbsent(rows.getString(1), schema -> new ArrayList<>()).add(rows.getString(2));
                }
            }
            Map<String, Integer> counts = new TreeMap<>();
            closed.forEach((schema, tables) -> counts.put(schema, tables.size()));
            assertThat(counts).as("the catalogue holds every refusal the migrations wrote").isEqualTo(new TreeMap<>(TABLES_CLOSED));
            assertThat(closed.get("ledger")).contains("journal_entry", "journal_line");
            for (Map.Entry<String, List<String>> schema : closed.entrySet()) {
                for (String table : schema.getValue()) {
                    String name = schema.getKey() + "." + table;
                    Savepoint before = migrator.setSavepoint();
                    try {
                        statement.execute("TRUNCATE " + name + " CASCADE");
                        throw new AssertionError("the owner truncated " + name);
                    } catch (SQLException refusal) {
                        assertThat(refusal.getSQLState()).as(name).isEqualTo("P0001");
                        assertThat(refusal.getMessage()).as("refused by %s's own trigger, not by one a cascade reached first", name)
                                .contains(name + " is never truncated");
                    } finally {
                        migrator.rollback(before);
                    }
                }
            }
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("every function of the eleven schemas names pg_catalog before each clock in its code, on the database itself")
    void noFunctionInTheDatabaseReadsAClockThroughThePath() throws SQLException {
        Map<String, Integer> readers = new TreeMap<>();
        List<String> open = new ArrayList<>();
        for (Map.Entry<String, String> function : definitions().entrySet()) {
            String code = NOT_CODE.matcher(function.getValue()).replaceAll(" ");
            if (A_QUALIFIED_CLOCK.matcher(code).find()) {
                readers.merge(function.getKey().substring(0, function.getKey().indexOf('.')), 1, Integer::sum);
            }
            if (A_CLOCK_THROUGH_THE_PATH.matcher(code).find()) {
                open.add(function.getKey());
            }
        }
        assertThat(open).as("a function a shadow could reach").isEmpty();
        FUNCTIONS_THAT_READ_A_CLOCK.forEach((schema, count) -> assertThat(readers.getOrDefault(schema, 0))
                .as("%s's readers were found, so an empty list above is not a scan that saw nothing", schema)
                .isGreaterThanOrEqualTo(count));
    }

    @Test
    @DisplayName("a shadowing schema first in the application role's path is read by an unqualified call, and moves no business date and no trigger's stamp")
    void aShadowFirstInTheApplicationRolesPathChangesNoJudgement() throws SQLException {
        try (Connection session = shadowedApplicationSession(); Statement statement = session.createStatement()) {
            assertThat(isAfterTheShadow(statement, "statement_timestamp()"))
                    .as("the positive control: an unqualified call in this session reads the shadow").isFalse();
            assertThat(isAfterTheShadow(statement, "bnpl.business_date_latest('UTC')"))
                    .as("the helper bnpl's date judgements read").isTrue();
            UUID id = UUID.randomUUID();
            statement.execute("INSERT INTO risk.monitored_transaction (id, journal_entry_id, journal_sequence, entry_type, posting_namespace, kind,"
                    + " counterparty_kind, amount_minor, currency, scale, value_date, posted_at, recorded_at) VALUES ('" + id + "', '" + UUID.randomUUID() + "',"
                    + " 1, 'POSTING', 'a-shadow-must-not-date-this', 'TRANSFER', 'NONE', 100, 'USD', 2, date '2026-01-01',"
                    + " timestamptz '2026-01-01 00:00:00+00', timestamptz '2000-01-01 00:00:00+00')");
            try (ResultSet row = statement.executeQuery("SELECT recorded_at FROM risk.monitored_transaction WHERE id = '" + id + "'")) {
                row.next();
                assertThat(row.getObject(1, OffsetDateTime.class).getYear()).as("stamped by the database's clock, not the shadow's").isGreaterThan(2000);
            }
            session.rollback();
        }
    }

    @Test
    @DisplayName("the fixture a suite steps the clock with opens the path only while it is installed, and puts every definition back byte for byte")
    void theFixtureOpensTheClockAndPutsEveryDefinitionBack() throws SQLException {
        Map<String, String> before = definitions();
        ReopenedClock.reopen();
        try (Connection session = shadowedApplicationSession(); Statement statement = session.createStatement()) {
            assertThat(isAfterTheShadow(statement, "bnpl.business_date_latest('UTC')"))
                    .as("with the fixture installed, a shadow on the path reaches the helper again").isFalse();
            session.rollback();
        } finally {
            ReopenedClock.restore();
        }
        assertThat(definitions()).as("the production definitions are back, byte for byte").isEqualTo(before);
    }

    private static Connection shadowedApplicationSession() throws SQLException {
        Connection connection = DatabaseRoles.application();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path = " + SHADOW + ", pg_catalog, \"$user\", public");
        }
        connection.setAutoCommit(false);
        return connection;
    }

    private static boolean isAfterTheShadow(Statement statement, String expression) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT " + expression + " > date '2001-01-01'")) {
            row.next();
            return row.getBoolean(1);
        }
    }

    /** {@code schema.name(arguments)} to its definition, for every function of the eleven schemas with no path of its own. */
    private static Map<String, String> definitions() throws SQLException {
        Map<String, String> definitions = new TreeMap<>();
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery("SELECT n.nspname || '.' || p.proname || '(' || pg_catalog.pg_get_function_identity_arguments(p.oid) || ')',"
                        + " pg_catalog.pg_get_functiondef(p.oid) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE p.prokind = 'f' AND p.proconfig IS NULL AND n.nspname = ANY (ARRAY['accounting', 'bnpl', 'checkout', 'credit', 'crossborder',"
                        + " 'fx', 'ledger', 'merchant', 'payments', 'risk', 'settlement'])")) {
            while (rows.next()) {
                definitions.put(rows.getString(1), rows.getString(2));
            }
        }
        return definitions;
    }
}
