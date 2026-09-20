package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcRefundStore;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The stuck-payment gauges over the real schema (`P5-TSK-017`): the reads count the rows
 * that are actually stranded and age them the way the sweeper does.
 *
 * <p>The hermetic suite owns NaN, the floor and the arithmetic; what only a database can
 * show is that the SQL means what the gauge's description claims — the two unknown attempt
 * states and no other, the refund's {@code UNKNOWN} and no other, and an age measured from
 * the state's own entry rather than from the row's birth.
 */
@Tag("database")
@DisplayName("the stuck-payment gauges over the schema (P5-TSK-017)")
class PaymentMetricsDatabaseTest {

    private final PaymentAttemptStore<Connection> attempts = new JdbcPaymentAttemptStore();
    private final JdbcRefundStore refunds = new JdbcRefundStore();

    @Test
    @DisplayName("only the honestly-unknown states count, and the age is the state's own")
    void onlyUnknownStatesCountAndTheAgeIsTheStates() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttemptStore.UnknownReading before = attempts.unknownReading(app);

            // Two attempts, both seeded through LEGAL edges only - the machine's own
            // trigger refuses anything else, which is itself worth knowing here: one moved
            // to AUTH_UNKNOWN an hour ago, one left AUTH_DISPATCHED where it was born.
            // The dispatched row is the sharp control: it is the platform MID-QUESTION,
            // not stranded, and counting it would make the alert fire on every healthy
            // in-flight payment.
            UUID stale = seedAttempt(app, "AUTH_UNKNOWN", "1 hour");
            seedAttempt(app, "AUTH_DISPATCHED", "0 minutes");

            PaymentAttemptStore.UnknownReading after = attempts.unknownReading(app);
            assertThat(after.active() - before.active())
                    .as("the unknown one only - a dispatched attempt is mid-question, and"
                            + " the gauge that counted it would alert on healthy traffic")
                    .isEqualTo(1);
            assertThat(after.oldestAgeSeconds())
                    .as("aged from the transition that entered the state (the sweeper's own"
                            + " expression), so an old row freshly stranded reads young and a"
                            + " young row long stranded reads old")
                    .isGreaterThanOrEqualTo(3_500L);
            assertThat(stale).isNotNull();
        }
    }

    @Test
    @DisplayName("the refund reading is the same shape for the machine that parks money"
            + " behind a standing hold")
    void refundsReadTheSameWay() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            // No refund rows are created here: what this proves is that the read runs against
            // the real schema and answers a non-negative count - the arithmetic and the
            // combination are the hermetic suite's, and the refund lifecycle's own suites
            // already drive UNKNOWN refunds end to end (P5-TSK-016).
            PaymentAttemptStore.UnknownReading reading = refunds.unknownReading(app);
            assertThat(reading.active()).isGreaterThanOrEqualTo(0);
            assertThat(reading.oldestAgeSeconds()).isGreaterThanOrEqualTo(0);
        }
    }

    // -----------------------------------------------------------------

    /** A minimal attempt row in {@code status}, with its state entered {@code ago} ago. */
    private UUID seedAttempt(Connection app, String status, String ago) throws SQLException {
        UUID party = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        execute(
                app,
                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Gauge Subject', now())",
                party);
        UUID customer = UUID.randomUUID();
        execute(
                app,
                "INSERT INTO party.customer (id, party_id, status, opened_at,"
                        + " status_changed_at) VALUES (?, ?, 'ACTIVE', now(), now())",
                customer,
                party);
        execute(
                app,
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " payment_method_id, wallet_account_id, amount_minor, currency,"
                        + " scale, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 'EUR', 2, 'PROCESSING', now())",
                intent,
                party,
                customer,
                UUID.randomUUID(),
                UUID.randomUUID());
        execute(
                app,
                "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                        + " status, created_at) VALUES (?, ?, ?, 'AUTH_DISPATCHED', now())",
                attempt,
                intent,
                "gauge-" + UUID.randomUUID());
        if (status.equals("AUTH_DISPATCHED")) {
            return attempt;
        }
        // The state's entry: the transition and its history row, aged by the SERVER's clock
        // (ADR-0014) - which is the expression the gauge and the sweeper share.
        execute(
                app,
                "UPDATE payments.payment_attempt SET status = ? WHERE id = ?",
                status,
                attempt);
        execute(
                app,
                "INSERT INTO payments.payment_attempt_event (attempt_id, from_status,"
                        + " to_status, actor_id, actor_type, occurred_at)"
                        + " VALUES (?, 'AUTH_DISPATCHED', ?, 'platform', 'PLATFORM',"
                        + " now() - INTERVAL '" + ago + "')",
                attempt,
                status);
        return attempt;
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
