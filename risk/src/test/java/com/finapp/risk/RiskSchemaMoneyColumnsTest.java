package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.MoneyColumnFloor;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The risk schema's money columns, against the catalogue (`P13-TSK-006`; {@code PHASE_13_PLAN.md} section 8;
 * {@code INV-MON-01}, {@code INV-RSK-02}, {@code INV-RSK-03}) - the lending and bnpl floor ({@link MoneyColumnFloor}, one
 * judgement, never a copy) with <strong>exactly one</strong> admitted exception: risk holds no balance, every amount sits
 * on an {@code INSERT}-only row, and the velocity window's two counters - {@code count} and {@code sum_minor} - are the
 * only updatable columns of any relation carrying money in the schema, updatable by the application role through the
 * one-statement upsert {@code risk V005} admits (its trigger holds the update to +1 and a non-decreasing sum for every
 * role). Anything else the floor reports - a third updatable column on the window, an {@code UPDATE} on any other money
 * table, a {@code DELETE}, a non-{@code bigint} {@code *_minor}, a floating-point column - fails.
 *
 * <p><strong>Why a risk guard is warranted now.</strong> {@code sum_minor} is risk's first money column a role may
 * update; until this task the schema needed no exception and no guard said so. The exception is stated as two exact
 * lines, so it cannot widen silently, and the planted cases prove the guard can fail. <strong>Written and owed</strong>
 * (owner decision C5).
 */
@Tag("database")
@DisplayName("risk's money columns: bigint, INSERT-only, the velocity counters the one exception (P13-TSK-006)")
class RiskSchemaMoneyColumnsTest {

    private static final String SCHEMA = "risk";

    /** The admitted exception, verbatim as the floor reports it - the upsert's two columns, the application's alone. */
    static final List<String> THE_UPSERTS_COLUMNS = List.of(
            "velocity_window.count: carries money and finapp_app holds column UPDATE - not INSERT-only",
            "velocity_window.sum_minor: carries money and finapp_app holds column UPDATE - not INSERT-only");

    private static Connection migrator;

    @BeforeAll
    static void connect() throws SQLException {
        migrator = DatabaseRoles.migrator();
        migrator.setAutoCommit(false);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (migrator != null) {
            migrator.rollback();
            migrator.close();
        }
    }

    @Test
    @DisplayName("the live schema breaches the floor in exactly the velocity window's two counters, and nowhere else")
    void theLiveSchemaHoldsTheFloorButTheCounters() throws SQLException {
        try {
            assertThat(MoneyColumnFloor.violations(migrator, SCHEMA)).containsExactlyInAnyOrderElementsOf(THE_UPSERTS_COLUMNS);
        } finally {
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("a third updatable column on the window, an UPDATE on another money table, a DELETE and a float are each"
            + " reported beyond the exception")
    void theExceptionCannotWiden() throws SQLException {
        try (Statement statement = migrator.createStatement()) {
            statement.execute("GRANT UPDATE (currency) ON risk.velocity_window TO finapp_app");
            statement.execute("CREATE TABLE risk.planted_mutable_money (id uuid, amount_minor bigint, rate double precision)");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON risk.planted_mutable_money TO finapp_app");
            List<String> violations = MoneyColumnFloor.violations(migrator, SCHEMA);
            assertThat(violations).containsAll(THE_UPSERTS_COLUMNS);
            assertThat(violations).anySatisfy(v -> assertThat(v).contains("velocity_window.currency", "UPDATE"));
            assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_mutable_money", "UPDATE"));
            assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_mutable_money", "DELETE"));
            assertThat(violations).anySatisfy(v -> assertThat(v).contains("planted_mutable_money.rate", "double precision"));
        } finally {
            migrator.rollback();
        }
    }
}
