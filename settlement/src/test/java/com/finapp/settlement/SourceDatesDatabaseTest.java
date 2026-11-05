package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V018` against PostgreSQL (`P14-TSK-025`; the owner's rulings of 2026-11-06 on `P14-TSK-012`'s (c)): the backfill, the default
 * dropped, and the days moving only towards requiring more, for every writer. <strong>Written and owed</strong> (owner decision C5); the text's
 * half is {@code SettlementV018MigrationTest}, on every build.
 *
 * <p><strong>Under any order</strong>: every case's writes are in a transaction it rolls back ({@link #rollBack}), on the seeded
 * {@code simulated-psp.settlement} row, so no case reads another's and the shared fixture is as {@code V018} left it.
 */
@Tag("database")
@DisplayName("settlement V018 - a source's admission and retirement days, in the database (P14-TSK-025)")
class SourceDatesDatabaseTest {

    /** PostgreSQL's SQLStates: a refused privilege, a NOT NULL, a CHECK, a trigger's {@code RAISE}. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String NOT_NULL = "23502";
    private static final String CHECK = "23514";
    private static final String RAISED = "P0001";
    private static final String PSP = "simulated-psp.settlement";
    private static final List<String> SEEDED = List.of("simulated-psp.settlement", "simulated-scheme.cycle-report", "simulated-payout.settlement",
            "simulated-bank.statement", "fx-sim-a.trade-report", "corridor-sim-a.settlement", "fx-sim-b.trade-report", "corridor-sim-b.settlement");

    private static Connection application;
    private static Connection migrator;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        migrator = DatabaseRoles.migrator();
        migrator.setAutoCommit(false);
    }

    @AfterEach
    void rollBack() throws SQLException {
        application.rollback();
        migrator.rollback();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        application.close();
        migrator.close();
    }

    @Test
    @DisplayName("the backfill: the eight seeded sources admitted 1970-01-01 and never retired; the default dropped, so a source inserted without its day is refused")
    void theBackfillExcusesNoMonth() throws SQLException {
        for (String code : SEEDED) {
            assertThat(one(migrator, "SELECT admitted_on || '|' || coalesce(retired_on::text, 'none') FROM settlement.source WHERE code = '" + code + "'"))
                    .as(code).isEqualTo("1970-01-01|none");
        }
        assertThat(one(migrator, "SELECT coalesce(column_default, 'none') FROM information_schema.columns WHERE table_schema = 'settlement'"
                + " AND table_name = 'source' AND column_name = 'admitted_on'")).isEqualTo("none");
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(migrator, "INSERT INTO settlement.source (id, code, kind, status, next_sequence)"
                        + " VALUES (gen_random_uuid(), 'undated.settlement', 'PSP_SETTLEMENT_REPORT', 'ACTIVE', 1)"))
                .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(NOT_NULL));
    }

    @Test
    @DisplayName("the admission moves only earlier, for the owner itself: a later day would excuse a month the source served")
    void theAdmissionNeverMovesLater() throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET admitted_on = DATE '1970-01-02' WHERE code = '" + PSP + "'"))
                .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(RAISED))
                .withMessageContaining("never moves later");
        migrator.rollback();
        execute(migrator, "UPDATE settlement.source SET admitted_on = DATE '1969-12-31' WHERE code = '" + PSP + "'");
        assertThat(one(migrator, "SELECT admitted_on::text FROM settlement.source WHERE code = '" + PSP + "'")).isEqualTo("1969-12-31");
    }

    @Test
    @DisplayName("a retirement by the status edge, the application's own grant, is stamped with the database's UTC day; the application writes neither day itself")
    void aRetirementIsDated() throws SQLException {
        execute(application, "UPDATE settlement.source SET status = 'RETIRED' WHERE code = '" + PSP + "'");
        assertThat(one(application, "SELECT (retired_on = (now() AT TIME ZONE 'UTC')::date)::text FROM settlement.source WHERE code = '" + PSP + "'"))
                .isEqualTo("true");
        application.rollback();
        for (String column : List.of("admitted_on = DATE '1969-01-01'", "retired_on = DATE '2000-01-01'")) {
            assertThatExceptionOfType(SQLException.class)
                    .as(column).isThrownBy(() -> execute(application, "UPDATE settlement.source SET " + column + " WHERE code = '" + PSP + "'"))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            application.rollback();
        }
    }

    @Test
    @DisplayName("a retirement day stated with the edge stands; it is never moved earlier, never cleared, never ahead of today, never before the admission, and never while ACTIVE")
    void theRetirementMovesOnlyLater() throws SQLException {
        execute(migrator, "UPDATE settlement.source SET status = 'RETIRED', retired_on = DATE '2026-01-31' WHERE code = '" + PSP + "'");
        assertThat(one(migrator, "SELECT retired_on::text FROM settlement.source WHERE code = '" + PSP + "'")).isEqualTo("2026-01-31");
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET retired_on = DATE '2026-01-30' WHERE code = '" + PSP + "'"))
                .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(RAISED))
                .withMessageContaining("never cleared or moved earlier");
        migrator.rollback();

        execute(migrator, "UPDATE settlement.source SET status = 'RETIRED', retired_on = DATE '2026-01-31' WHERE code = '" + PSP + "'");
        execute(migrator, "UPDATE settlement.source SET retired_on = DATE '2026-02-01' WHERE code = '" + PSP + "'");
        assertThat(one(migrator, "SELECT retired_on::text FROM settlement.source WHERE code = '" + PSP + "'")).as("later requires more").isEqualTo("2026-02-01");
        migrator.rollback();

        List<String[]> refusals = new ArrayList<>();
        refusals.add(new String[] {"status = 'RETIRED', retired_on = (now() AT TIME ZONE 'UTC')::date + 1", RAISED});
        refusals.add(new String[] {"status = 'RETIRED', retired_on = DATE '1960-01-01'", CHECK});
        refusals.add(new String[] {"retired_on = DATE '2026-01-31'", CHECK});
        for (String[] refusal : refusals) {
            assertThatExceptionOfType(SQLException.class)
                    .as(refusal[0]).isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET " + refusal[0] + " WHERE code = '" + PSP + "'"))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(refusal[1]));
            migrator.rollback();
        }

        execute(migrator, "UPDATE settlement.source SET status = 'RETIRED', retired_on = DATE '2026-01-31' WHERE code = '" + PSP + "'");
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET retired_on = NULL WHERE code = '" + PSP + "'"))
                .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(RAISED));
    }

    @Test
    @DisplayName("V002's rules stand in the replaced trigger: identity frozen, retirement one-way, the sequence forward")
    void theOldRulesStand() throws SQLException {
        for (String[] refusal : List.of(
                new String[] {"code = 'renamed.source'", "frozen"},
                new String[] {"next_sequence = 0", "never walks backwards"})) {
            assertThatExceptionOfType(SQLException.class)
                    .as(refusal[0]).isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET " + refusal[0] + " WHERE code = '" + PSP + "'"))
                    .withMessageContaining(refusal[1]);
            migrator.rollback();
        }
        execute(migrator, "UPDATE settlement.source SET status = 'RETIRED', retired_on = DATE '2026-01-31' WHERE code = '" + PSP + "'");
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET status = 'ACTIVE', retired_on = NULL WHERE code = '" + PSP + "'"))
                .withMessageContaining("retires once");
        assertThat(LocalDate.EPOCH).as("the backfill's day").isEqualTo(LocalDate.of(1970, 1, 1));
    }

    @Test
    @DisplayName("the fix round's M-3: a retirement by the migrator, which holds UPDATE on retired_on, must state its day - stamped with today it would wedge the month - while"
            + " the application role's status edge, which cannot state it, is stamped")
    void aMigrationMustStateTheDay() throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(migrator, "UPDATE settlement.source SET status = 'RETIRED' WHERE code = '" + PSP + "'"))
                .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(RAISED))
                .withMessageContaining("must state it");
        migrator.rollback();
        assertThat(one(migrator, "SELECT status FROM settlement.source WHERE code = '" + PSP + "'")).isEqualTo("ACTIVE");

        execute(application, "UPDATE settlement.source SET status = 'RETIRED' WHERE code = '" + PSP + "'");
        assertThat(one(application, "SELECT (retired_on IS NOT NULL)::text FROM settlement.source WHERE code = '" + PSP + "'")).isEqualTo("true");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String one(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as(sql).isTrue();
            return row.getString(1);
        }
    }
}
