package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The BNPL storm's owner-rank adversaries (`P12-TST-001`; the lending storm's {@code LendingStormFaults}) - never paths the
 * platform has, each confined to a quiesced scene (DDL takes table locks and changes every writer's behaviour, so never
 * while the load blows):
 *
 * <ul>
 *   <li>a <strong>crash point</strong>: a row-conditioned {@code BEFORE} trigger created by the migrator that either
 *       raises (a forced rollback) or terminates its own backend mid-transaction (a killed backend) - only the rows the
 *       condition names, so every other writer is untouched; closing it drops it;
 *   <li>the <strong>planted projection drift</strong>: the ledger's {@code account_balance} projection of one obligation
 *       account moved off its journal lines, and put back - the due state is derived from rows and lines, never from it;
 *   <li>an instance's <strong>backends terminated</strong> by its pool's application name - the restart's crash.
 * </ul>
 */
final class BnplStormFaults {

    private BnplStormFaults() {}

    /**
     * A fault beneath {@code table}'s {@code operation} where {@code condition} holds: the statement refused (a forced
     * rollback) or, {@code kill}, the backend terminated mid-transaction. Closing lifts it.
     */
    static AutoCloseable refuse(String table, String operation, String condition, boolean kill) throws SQLException {
        String name = "storm_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        String act = kill ? "PERFORM pg_terminate_backend(pg_backend_pid()); PERFORM pg_sleep(5);"
                : "RAISE EXCEPTION 'the storm''s injected fault';";
        try (Connection owner = DatabaseRoles.bootstrap(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN " + act + " END IF; RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + schema + "." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.bootstrap(); Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON " + table);
                ddl.execute("DROP FUNCTION " + schema + "." + name + "()");
            }
        };
    }

    /** Moves the projection of {@code obligation}'s {@code purpose} account by {@code delta} minor units, off its lines. */
    static void plantProjectionDrift(String obligation, String purpose, long delta) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                try (PreparedStatement move = owner.prepareStatement("UPDATE ledger.account_balance b SET posted_minor ="
                        + " posted_minor + ? FROM ledger.ledger_account a WHERE a.id = b.ledger_account_id AND"
                        + " a.owner_ref = ?::uuid AND a.purpose = ?")) {
                    move.setLong(1, delta);
                    move.setString(2, obligation);
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
