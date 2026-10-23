package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * payments {@code V033} live (`P13-TSK-009`; {@code INV-LIFE-01}/{@code -02}/{@code -04}, {@code INV-BAL-04}): the
 * judged births, the held edges and the held facts' freeze bind every writer - the application role and the migrator
 * that owns the table alike - and every name the migration declared is in the catalogue once. The text of the migration
 * is {@code PaymentsMigrationTest}'s (hermetic); this is the database's own word. <strong>Written and owed</strong>
 * (C5).
 */
@Tag("database")
@DisplayName("payments V033: the held withdrawal's machine, live (P13-TSK-009)")
class WithdrawalMigrationDatabaseTest {

    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.systemUTC(), new java.security.SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";

    @Test
    @DisplayName("a withdrawal is born as its judgement decides: DISPATCHED, HELD with its window, FAILED(RISK_REFUSED)"
            + " - never FAILED with the scheme's reason, HELD without a window, or a door's key at birth")
    void theJudgedBirths() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            insert(app, IDS.next(), "DISPATCHED", null, false, null);
            insert(app, IDS.next(), "HELD", null, true, null);
            insert(app, IDS.next(), "FAILED", "RISK_REFUSED", false, null);
            assertSqlState(CHECK_VIOLATION, () -> insert(app, IDS.next(), "FAILED", "DECLINED", false, null));
            assertSqlState(CHECK_VIOLATION, () -> insert(app, IDS.next(), "HELD", null, false, null));
            assertSqlState(CHECK_VIOLATION, () -> insert(app, IDS.next(), "DISPATCHED", null, true, null));
            assertSqlState(CHECK_VIOLATION, () -> insert(app, IDS.next(), "UNKNOWN", null, false, null));
            assertSqlState(CHECK_VIOLATION, () -> insert(app, IDS.next(), "HELD", null, true, "risk.action:born"));
        }
    }

    @Test
    @DisplayName("HELD leaves only to DISPATCHED (its permit moved, its key recorded) or FAILED(RISK_REFUSED); the scheme"
            + " never fails it; RISK_REFUSED never follows a dispatch; nothing leaves a terminal state")
    void theHeldEdges() throws Exception {
        UUID released = IDS.next();
        UUID denied = IDS.next();
        UUID dispatched = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insert(app, released, "HELD", null, true, null);
            insert(app, denied, "HELD", null, true, null);
            insert(app, dispatched, "DISPATCHED", null, false, null);
            assertSqlState(RAISED, () -> update(app, "status = 'COMPLETED', scheme_reference = 'sch-x'", released));
            assertSqlState(RAISED, () -> update(app, "status = 'UNKNOWN'", released));
            assertSqlState(RAISED, () -> update(app, "status = 'FAILED', failure_reason = 'DECLINED'", denied));
            assertSqlState(RAISED, () -> update(app, "status = 'FAILED', failure_reason = 'RISK_REFUSED'", dispatched));
            assertThat(update(app, "status = 'DISPATCHED', last_dispatched_at = statement_timestamp(),"
                    + " held_resolution_key = 'risk.action:r'", released)).isEqualTo(1);
            assertThat(update(app, "status = 'FAILED', failure_reason = 'RISK_REFUSED',"
                    + " held_resolution_key = 'risk.action:d'", denied)).isEqualTo(1);
            assertSqlState(RAISED, () -> update(app, "status = 'HELD'", denied));
            assertSqlState(RAISED, () -> update(app, "status = 'DISPATCHED'", denied));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The machine binds the owner too: nothing re-enters HELD.
            assertSqlState(RAISED, () -> update(migrator, "status = 'HELD'", dispatched));
        }
    }

    @Test
    @DisplayName("the held facts are frozen: the window never moves, the key and the detail arrive only with the edge"
            + " out of HELD and never move after; the expiry's detail rides only a key-less RISK_REFUSED")
    void theHeldFactsAreFrozen() throws Exception {
        UUID held = IDS.next();
        UUID expired = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insert(app, held, "HELD", null, true, null);
            insert(app, expired, "HELD", null, true, null);
            assertThatThrownBy(() -> update(app, "held_until = held_until + interval '1 hour'", held))
                    .as("the app holds no grant on the window").isInstanceOf(SQLException.class);
            assertSqlState(RAISED, () -> update(app, "held_resolution_key = 'risk.action:early'", held));
            assertThat(update(app, "status = 'FAILED', failure_reason = 'RISK_REFUSED',"
                    + " failure_detail = 'HOLD_EXPIRED'", expired)).isEqualTo(1);
            assertSqlState(RAISED, () -> update(app, "failure_detail = NULL", expired));
            assertSqlState(CHECK_VIOLATION, () -> update(app, "status = 'FAILED', failure_reason = 'RISK_REFUSED',"
                    + " failure_detail = 'HOLD_EXPIRED', held_resolution_key = 'risk.action:both'", held));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertSqlState(RAISED, () -> update(migrator, "held_until = held_until + interval '1 hour'", held));
            assertSqlState(RAISED, () -> update(migrator, "held_at = NULL, held_until = NULL", expired));
        }
    }

    @Test
    @DisplayName("every constraint, index and trigger V033 declared is in the catalogue once, the birth trigger replaced")
    void theNamesAreInTheCatalogue() throws Exception {
        List<String> found = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'payments.withdrawal'::regclass"
                                // CHECKs only: a primary or unique key is listed again as its index.
                                + " AND contype = 'c'"
                                + " UNION ALL SELECT indexname FROM pg_indexes WHERE schemaname = 'payments'"
                                + " AND tablename = 'withdrawal'"
                                + " UNION ALL SELECT tgname FROM pg_trigger WHERE tgrelid = 'payments.withdrawal'::regclass"
                                + " AND NOT tgisinternal");
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        assertThat(found).doesNotHaveDuplicates()
                .contains("withdrawal_held_window_is_whole", "withdrawal_held_window_follows_creation",
                        "withdrawal_held_carries_its_window", "withdrawal_failure_detail_is_known",
                        "withdrawal_failure_detail_is_a_held_risk_refusal", "withdrawal_held_resolution_key_bounded",
                        "withdrawal_held_resolution_key_needs_a_hold", "withdrawal_hold_expiry",
                        "withdrawal_is_born_judged", "withdrawal_permits_only_machine_edges")
                .doesNotContain("withdrawal_is_born_dispatched");
    }

    // ------------------------------------------------------------------ raw rows

    private static void insert(Connection connection, UUID id, String status, String failureReason, boolean held,
            String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.withdrawal (id, party_id, customer_id, wallet_account_id, payment_method_id,"
                        + " destination_reference, amount_minor, currency, scale, end_to_end_reference, rail, status,"
                        + " failure_reason, dispatch_key, hold_reference, created_at, last_dispatched_at, held_at,"
                        + " held_until, held_resolution_key) VALUES (?, ?, ?, ?, ?, ?, 1000, 'EUR', 2, ?, 'instant', ?,"
                        + " ?, ?, ?, statement_timestamp(), statement_timestamp(),"
                        + " CASE WHEN ? THEN statement_timestamp() END,"
                        + " CASE WHEN ? THEN statement_timestamp() + interval '72 hours' END, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, IDS.next());
            insert.setObject(3, IDS.next());
            insert.setObject(4, IDS.next());
            insert.setObject(5, IDS.next());
            insert.setString(6, "dest-v033-" + id.toString().substring(0, 8));
            insert.setString(7, id.toString().replace("-", ""));
            insert.setString(8, status);
            insert.setString(9, failureReason);
            insert.setString(10, "key-" + id);
            insert.setObject(11, IDS.next());
            insert.setBoolean(12, held);
            insert.setBoolean(13, held);
            insert.setString(14, key);
            insert.executeUpdate();
        }
    }

    private static int update(Connection connection, String set, UUID id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE payments.withdrawal SET " + set + " WHERE id = ?")) {
            update.setObject(1, id);
            return update.executeUpdate();
        }
    }

    private interface SqlAction {
        void run() throws SQLException;
    }

    private static void assertSqlState(String state, SqlAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo(state));
    }
}
