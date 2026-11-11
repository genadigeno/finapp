package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reporting pool refuses a role able to write, under any name, at a real database (`P15-TSK-043`; {@code THREAT_MODEL.md}
 * §6 item 36 (g), T44). Owed to the exit database pass (C14); walked by hand at the task's gate, and the same statement run
 * through the real pool, Hikari and the driver on PostgreSQL 18.6 by hand there too.
 *
 * <p>Each planted role is created by the container's bootstrap superuser with a login of this run's own, granted
 * {@code CONNECT} (the local roles script revokes it from {@code PUBLIC}), and dropped after. None is named for a writer, so
 * {@code ReportingPoolBeans}' name check would start every one of them: only the capability check can refuse them. The
 * positive control is the real {@code finapp_reporting}, which must open.
 */
@Tag("database")
@DisplayName("the reporting pool refuses a role able to write under any name, and opens as the reporting role (P15-TSK-043)")
class ReportingPoolCapabilityDatabaseTest {

    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final String suffix = HexFormat.of().formatHex(RANDOMNESS.generateSeed(4));
    private final String login = HexFormat.of().formatHex(RANDOMNESS.generateSeed(16));
    private final List<String> planted = new ArrayList<>();

    @Test
    @DisplayName("finapp_app's rights through a chain of two memberships: refused, by MEMBER, whatever the role is called")
    void aMemberOfTheWriterThroughAChainIsRefused() throws SQLException {
        String middle = role("rp_middle_", false);
        String chained = role("rp_chained_", true);
        bootstrap("GRANT finapp_app TO " + middle, "GRANT " + middle + " TO " + chained);

        assertRefused(chained, "can use the rights of finapp_app");
    }

    @Test
    @DisplayName("a role created NOINHERIT, as the platform creates its own, granted finapp_app with the defaults - a membership"
            + " that only lets it SET ROLE to the writer: refused, which a USAGE check admits")
    void aSettableMembershipIsRefused() throws SQLException {
        String settable = role("rp_settable_", true);
        bootstrap("ALTER ROLE " + settable + " NOINHERIT", "GRANT finapp_app TO " + settable);
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement();
                ResultSet usage = statement.executeQuery("SELECT pg_has_role('" + settable + "', 'finapp_app', 'USAGE'),"
                        + " pg_has_role('" + settable + "', 'finapp_app', 'MEMBER')")) {
            assertThat(usage.next()).isTrue();
            assertThat(usage.getBoolean(1)).as("the entry's USAGE would admit it").isFalse();
            assertThat(usage.getBoolean(2)).as("MEMBER sees it").isTrue();
        }

        assertRefused(settable, "can use the rights of finapp_app");
    }

    @Test
    @DisplayName("INSERT on a ledger table granted directly: refused, naming the table and the write")
    void anInsertOnTheLedgerIsRefused() throws SQLException {
        String inserting = role("rp_inserting_", true);
        bootstrap("GRANT USAGE ON SCHEMA ledger TO " + inserting, "GRANT SELECT, INSERT ON ledger.journal_entry TO " + inserting);

        assertRefused(inserting, "can write ledger.journal_entry (INSERT)");
    }

    @Test
    @DisplayName("TRUNCATE on a report run's table: refused - every write on every platform table, not the ledger's INSERT alone")
    void aTruncateElsewhereIsRefused() throws SQLException {
        String truncating = role("rp_truncating_", true);
        bootstrap("GRANT TRUNCATE ON accounting.report_run TO " + truncating);

        assertRefused(truncating, "can write accounting.report_run (TRUNCATE)");
    }

    @Test
    @DisplayName("pg_write_all_data, the server's own writer: refused by what it can do")
    void thePredefinedWriterIsRefused() throws SQLException {
        String writing = role("rp_writing_", true);
        bootstrap("GRANT pg_write_all_data TO " + writing);

        assertRefused(writing, "can write ");
    }

    @Test
    @DisplayName("the positive control: finapp_reporting opens, reads, and the check ran on its connection")
    void theReportingRoleOpens() throws SQLException {
        try (ReportingPool pool = new ReportingPool(DatabaseRoles.required("finapp.db.url"), Map.of(),
                        DatabaseRoles.required("finapp.db.reporting.user"), DatabaseRoles.required("finapp.db.reporting.password"), 1);
                Connection connection = pool.open();
                Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT count(*) FROM ledger.journal_entry")) {
            assertThat(rows.next()).isTrue();
            assertThat(pool.connectionInitSql()).isEqualTo(ReportingPool.CAPABILITY_CHECK);
        }
    }

    @AfterEach
    void dropThePlantedRoles() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            for (String name : planted.reversed()) {
                statement.execute("DROP OWNED BY " + name);
                statement.execute("DROP ROLE " + name);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the world

    private void assertRefused(String role, String reason) throws SQLException {
        try (ReportingPool pool = new ReportingPool(DatabaseRoles.required("finapp.db.url"), Map.of(), role, login, 1)) {
            assertThatExceptionOfType(SQLException.class).as(role).isThrownBy(pool::open)
                    .satisfies(refused -> assertThat(messages(refused)).as(role).contains(reason));
        }
    }

    /** A role of this run's own: {@code CONNECT}ed, with this run's login where it signs in. */
    private String role(String prefix, boolean signsIn) throws SQLException {
        String name = prefix + suffix;
        String database;
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement();
                ResultSet current = statement.executeQuery("SELECT current_database()")) {
            current.next();
            database = current.getString(1);
        }
        bootstrap(signsIn ? "CREATE ROLE " + name + " LOGIN PASSWORD '" + login + "'" : "CREATE ROLE " + name + " NOLOGIN");
        planted.add(name);
        bootstrap("GRANT CONNECT ON DATABASE \"" + database + "\" TO " + name);
        return name;
    }

    private static void bootstrap(String... statements) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable at = failure; at != null; at = at.getCause()) {
            all.append(at.getMessage()).append('\n');
        }
        return all.toString();
    }
}
