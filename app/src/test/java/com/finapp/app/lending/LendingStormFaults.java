package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The lending storm's owner-rank adversaries (`P11-TST-001`, the design's sections 16.2 and 16.3) - never paths the
 * platform has, each confined to a quiesced scene (DDL takes table locks and changes every writer's behaviour, so never
 * while the load blows):
 *
 * <ul>
 *   <li>a <strong>crash point</strong>: a row-conditioned {@code BEFORE} trigger created by the migrator that either
 *       raises (a forced rollback) or terminates its own backend mid-transaction (a killed backend) - only the rows the
 *       condition names, so every other writer is untouched; closing it drops it;
 *   <li>the <strong>lock-bypass writer</strong> (scenario 4's unique's own rank): a duplicate {@code (loan, date)} accrual
 *       row written with the user triggers suspended - what a writer that skipped L3 and the ledger key would write;
 *   <li>the <strong>planted projection drift</strong> (scenario 3's "read the projection" reach): the ledger's
 *       {@code account_balance} projection of one account moved off its journal lines, and put back;
 *   <li>an instance's <strong>backends terminated</strong> by its pool's application name - the restart's crash.
 * </ul>
 */
final class LendingStormFaults {

    private LendingStormFaults() {}

    /**
     * A fault beneath {@code table}'s {@code operation} where {@code condition} holds: the statement refused (a forced
     * rollback) or, {@code kill}, the backend terminated mid-transaction. Closing lifts it.
     */
    static AutoCloseable refuse(String table, String operation, String condition, boolean kill) throws SQLException {
        String name = "storm_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        String act = kill ? "PERFORM pg_terminate_backend(pg_backend_pid()); PERFORM pg_sleep(5);"
                : "RAISE EXCEPTION 'the storm''s injected fault';";
        try (Connection owner = DatabaseRoles.migrator(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN " + act + " END IF; RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + schema + "." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.migrator(); Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON " + table);
                ddl.execute("DROP FUNCTION " + schema + "." + name + "()");
            }
        };
    }

    /**
     * The lock-bypass writer: one more {@code interest_accrual} row for {@code loan}'s latest accrued date - every column
     * copied but its posting (a zero day: nothing posted, so no entry, which no other unique can catch), a fresh id -
     * written by the bootstrap owner with the user triggers suspended. Under
     * {@code lending V010}'s {@code UNIQUE (loan_id, accrual_date)} the insert is refused ({@code 23505}) and nothing is
     * written: the answer is whether a duplicate now stands.
     */
    static boolean bypassWriterDuplicatesAnAccrual(String loan) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                try (PreparedStatement insert = owner.prepareStatement("INSERT INTO lending.interest_accrual (id, loan_id,"
                        + " accrual_date, period_start, period_end, currency, scale, base_minor, cumulative_exact,"
                        + " posted_minor, agreement_id, agreement_version, accrual_engine_version, journal_entry_id,"
                        + " created_at, correlation_id, recorded_order) SELECT ?, loan_id, accrual_date, period_start,"
                        + " period_end, currency, scale, base_minor, cumulative_exact, 0, agreement_id,"
                        + " agreement_version, accrual_engine_version, NULL, created_at, correlation_id, recorded_order"
                        + " FROM lending.interest_accrual WHERE loan_id = ?::uuid ORDER BY accrual_date DESC LIMIT 1")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, loan);
                    assertThat(insert.executeUpdate()).as("the loan has an accrual to duplicate").isEqualTo(1);
                }
                owner.commit();
                return true;
            } catch (SQLException refused) {
                owner.rollback();
                if ("23505".equals(refused.getSQLState())) {
                    return false;
                }
                throw refused;
            }
        }
    }

    /**
     * Moves the ledger's projection of {@code loan}'s {@code purpose} account by {@code delta} minor units (the owner,
     * the projection's guard suspended) - a drift the journal lines do not explain. {@link #repairProjection} puts it
     * back exactly.
     */
    static void plantProjectionDrift(String loan, String purpose, long delta) throws SQLException {
        moveProjection(loan, purpose, delta);
    }

    static void repairProjection(String loan, String purpose, long delta) throws SQLException {
        moveProjection(loan, purpose, -delta);
    }

    private static void moveProjection(String loan, String purpose, long delta) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                try (PreparedStatement move = owner.prepareStatement("UPDATE ledger.account_balance b SET posted_minor ="
                        + " posted_minor + ? FROM ledger.ledger_account a WHERE a.id = b.ledger_account_id AND"
                        + " a.owner_kind = 'LOAN' AND a.owner_ref = ?::uuid AND a.purpose = ?")) {
                    move.setLong(1, delta);
                    move.setString(2, loan);
                    move.setString(3, purpose);
                    assertThat(move.executeUpdate()).as("the account's projection row").isEqualTo(1);
                }
            }
            owner.commit();
        }
    }

    /** Every backend of the pool named {@code applicationName} terminated - an instance's crash, as the database sees it. */
    static int terminateBackends(String applicationName) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap();
                PreparedStatement terminate = owner.prepareStatement("SELECT count(*) FILTER (WHERE"
                        + " pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = ?")) {
            terminate.setString(1, applicationName);
            try (var row = terminate.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }
}
