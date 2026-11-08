package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A role's {@code search_path} moves no lending clock ({@code P15-TSK-004}, lending {@code V035}; the C15 debt row;
 * {@code THREAT_MODEL.md} T13). Owed to {@code P15-TST-002} (C14); the same check on the image's database is walked in
 * {@code infra/scripts/migrator-verify.sh}.
 */
@Tag("database")
@DisplayName("a shadowing schema first in a role's path moves no lending clock (P15-TSK-004)")
class LendingClockIgnoresTheSearchPathDatabaseTest {

    private static final String SHADOW = "shadowclock";
    private static final String ALL_THE_YEAR_2000 = "timestamptz '2000-01-01 00:00:00+00'";

    @BeforeAll
    static void aSchemaOfShadowClocks() throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SHADOW + "; GRANT USAGE ON SCHEMA " + SHADOW + " TO PUBLIC;");
            for (String clock : new String[] {"now", "statement_timestamp", "clock_timestamp", "transaction_timestamp"}) {
                statement.execute("CREATE OR REPLACE FUNCTION " + SHADOW + "." + clock + "() RETURNS timestamptz LANGUAGE sql STABLE AS"
                        + " $$ SELECT " + ALL_THE_YEAR_2000 + " $$");
            }
        }
    }

    @AfterAll
    static void noShadowLeftBehind() throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SHADOW + " CASCADE");
        }
    }

    private static Connection shadowedSession() throws SQLException {
        Connection connection = DatabaseRoles.application();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path = " + SHADOW + ", pg_catalog, \"$user\", public");
        }
        connection.setAutoCommit(false);
        return connection;
    }

    private static boolean afterTheShadow(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT " + sql + " > date '2001-01-01'")) {
            row.next();
            return row.getBoolean(1);
        }
    }

    @Test
    @DisplayName("the shadow is really on the path, and the business-date helpers the triggers judge by do not read it")
    void theHelpersReadTheDatabasesClock() throws SQLException {
        try (Connection session = shadowedSession()) {
            assertThat(afterTheShadow(session, "statement_timestamp()::date"))
                    .as("the positive control: an unqualified call in this session reads the shadow")
                    .isFalse();
            assertThat(afterTheShadow(session, "lending.business_date_latest('UTC')")).isTrue();
            assertThat(afterTheShadow(session, "lending.business_date_earliest('UTC')")).isTrue();
            session.rollback();
        }
    }

    @Test
    @DisplayName("a trigger's stamp reads the database's clock under the shadow")
    void aTriggersStampReadsTheDatabasesClock() throws SQLException {
        UUID id = UUID.randomUUID();
        String reference = String.format("LCAP-%012d", Math.abs(id.getLeastSignificantBits() % 1_000_000_000_000L));
        try (Connection session = shadowedSession(); Statement statement = session.createStatement()) {
            statement.execute("INSERT INTO lending.capital_contribution (id, currency, amount_minor, scale, reference, proposed_by,"
                    + " proposed_by_type, reason, proposed_at, correlation_id) VALUES ('" + id + "', 'USD', 100, 2,"
                    + " '" + reference + "', 'employee-1', 'EMPLOYEE',"
                    + " 'a shadow must not date this', '2000-01-01T00:00:00Z', 'correlation')");
            try (ResultSet row = statement.executeQuery("SELECT proposed_at FROM lending.capital_contribution WHERE id = '" + id + "'")) {
                row.next();
                assertThat(row.getObject(1, OffsetDateTime.class).getYear()).isGreaterThan(2000);
            }
            session.rollback();
        }
    }

    @Test
    @DisplayName("ClockStep re-opens the path only while it is installed, and puts every definition back")
    void theFixtureStepsTheTriggersAndRestoresThem() throws SQLException {
        String before = definitions();
        ClockStep.install();
        try {
            String stepped = ClockStep.inOneRolledBackTransaction(Duration.ofDays(2), uow -> {
                try (Statement statement = uow.createStatement();
                        ResultSet row = statement.executeQuery("SELECT lending.business_date_latest('UTC') > (pg_catalog.clock_timestamp() AT TIME ZONE 'UTC')::date")) {
                    row.next();
                    return row.getString(1);
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertThat(stepped).as("the fixture's frame reaches the helpers lending's triggers judge by").isEqualTo("t");
        } finally {
            ClockStep.uninstall();
        }
        assertThat(definitions()).as("the production definitions are back, byte for byte").isEqualTo(before);
    }

    private static String definitions() throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement();
                ResultSet row = statement.executeQuery("SELECT string_agg(pg_catalog.pg_get_functiondef(p.oid), '' ORDER BY p.oid)"
                        + " FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE n.nspname = 'lending' AND p.prokind = 'f'")) {
            row.next();
            return row.getString(1);
        }
    }
}
