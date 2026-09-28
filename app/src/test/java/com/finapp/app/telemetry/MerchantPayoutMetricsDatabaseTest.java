package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.JdbcMerchantPayoutStore;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The stuck-payout gauges over the real schema (`P6-TSK-013`).
 *
 * <p>The hermetic suite owns NaN, the floor and the publication; what only a database can show
 * is that the reading means what the gauges' descriptions claim. It counts every
 * {@code UNKNOWN} payout and every {@code DISPATCHED} one past the sweep's bound, never an
 * in-flight dispatch and never a resolved payout, and it ages each the sweep's way: an
 * {@code UNKNOWN} from its entry into that state, a dispatch from its latest permit.
 *
 * <p>Every row is seeded through the machine's LEGAL edges only — {@code V007}'s triggers refuse
 * anything else — and the figures are deltas and bounds, because the schema is shared with
 * every other suite in the run.
 */
@Tag("database")
@DisplayName("the stuck-payout gauges over the schema (P6-TSK-013)")
class MerchantPayoutMetricsDatabaseTest {

    /** The sweep's default dispatched bound — the one placeholder both read. */
    private static final Duration BOUND = Duration.ofMinutes(10);

    private final MerchantPayoutStore<Connection> payouts = new JdbcMerchantPayoutStore();

    @Test
    @DisplayName("UNKNOWN counts from its entry; DISPATCHED counts only past the sweep's bound,"
            + " from its permit; a resolved payout never counts")
    void theReadingIsTheSweepsOwnCandidacy() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            UUID merchant = merchant(app);
            UUID destination = effectiveDestination(app, merchant);
            MerchantPayoutStore.UnknownReading before = payouts.unknownReading(app, BOUND);
            MerchantPayoutStore.UnknownReading beforeUnbounded =
                    payouts.unknownReading(app, Duration.ZERO);

            // In flight: dispatched a minute ago. Mid-question - the P5-TSK-017 control, and the
            // reason the gauge would alert on healthy traffic if it counted every dispatch. A
            // minute, not a second: the unbounded reading below must find its permit in the past
            // of the same server clock that wrote it, and the Docker VM's clock steps back by
            // seconds every ~27 s (1.6 s at P7-TSK-015's gate, 2.8 s re-measured the same day).
            // Still nine minutes inside the bound.
            payout(app, merchant, destination, "1 minute");
            // Overdue: dispatched two hours ago and never answered. What an unknown-only gauge
            // misses when the sweep is not running - a merchant's money held with nothing saying so.
            payout(app, merchant, destination, "2 hours");
            // Resolved three days ago: FAILED is an answer, however old it is.
            UUID failed = payout(app, merchant, destination, "3 days");
            move(app, failed, "FAILED", "DECLINED", "3 days");
            // Born five days ago, UNKNOWN for one minute: its wait is the state's, not the row's.
            UUID unknown = payout(app, merchant, destination, "5 days");
            move(app, unknown, "UNKNOWN", null, "1 minute");

            MerchantPayoutStore.UnknownReading after = payouts.unknownReading(app, BOUND);
            assertThat(after.active() - before.active())
                    .as("the overdue dispatch and the unknown payout - never the in-flight"
                            + " dispatch, never the resolved one")
                    .isEqualTo(2);
            assertThat(after.oldestAgeSeconds())
                    // Less a minute: the age is the database's now() again, floored to whole
                    // seconds, and a clock step-back since the insert must not read as a defect
                    // (X-TSK-005).
                    .as("the overdue dispatch has waited two hours since its permit")
                    .isGreaterThanOrEqualTo(Duration.ofHours(2).minusMinutes(1).toSeconds())
                    .as("and the unknown payout is aged from its entry, not its five-day-old"
                            + " birth - a row born long ago and freshly stranded reads young")
                    .isLessThan(Duration.ofDays(5).toSeconds() - 60);

            MerchantPayoutStore.UnknownReading unbounded =
                    payouts.unknownReading(app, Duration.ZERO);
            assertThat(unbounded.active() - beforeUnbounded.active())
                    .as("with no bound the in-flight dispatch counts too: the sweep's bound is"
                            + " exactly what keeps healthy traffic out of the alert")
                    .isEqualTo(3);
        }
    }

    // -----------------------------------------------------------------

    private static UUID merchant(Connection app) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                app,
                "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now() - interval '6 days',"
                        + " now() - interval '6 days')",
                id,
                UUID.randomUUID());
        return id;
    }

    private static UUID effectiveDestination(Connection app, UUID merchant) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                app,
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '6 days',"
                        + " 'fixture', 'fixture-b', now() - interval '6 days', now() - interval"
                        + " '5 days 12 hours', now() - interval '5 days 6 hours')",
                id,
                merchant,
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return id;
    }

    /** A payout born {@code DISPATCHED} {@code ago}, its first permit at birth. */
    private static UUID payout(Connection app, UUID merchant, UUID destination, String ago)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                app,
                "INSERT INTO merchant.merchant_payout (id, merchant_id, amount_minor, currency,"
                        + " scale, destination_id, hold_reference, provider_idempotency_reference,"
                        + " provider_reference, status, failure_reason, dispatch_key, requested_by,"
                        + " requested_by_type, reason, created_at, last_dispatched_at) VALUES"
                        + " (?, ?, 100, 'EUR', 2, ?, ?, ?, NULL, 'DISPATCHED', NULL, ?, 'fixture',"
                        + " 'MERCHANT', NULL, now() - CAST(? AS interval),"
                        + " now() - CAST(? AS interval))",
                id,
                merchant,
                destination,
                UUID.randomUUID(),
                "pyo-" + UUID.randomUUID(),
                "gauge-" + UUID.randomUUID(),
                ago,
                ago);
        return id;
    }

    /** One legal edge out of {@code DISPATCHED}, with the history row the sweep ages it from. */
    private static void move(
            Connection app, UUID payout, String to, String failureReason, String ago)
            throws SQLException {
        execute(
                app,
                "UPDATE merchant.merchant_payout SET status = ?, failure_reason = ? WHERE id = ?",
                to,
                failureReason,
                payout);
        execute(
                app,
                "INSERT INTO merchant.merchant_payout_event (payout_id, from_status, to_status,"
                        + " actor_id, actor_type, occurred_at) VALUES (?, 'DISPATCHED', ?,"
                        + " 'platform', 'SYSTEM', now() - CAST(? AS interval))",
                payout,
                to,
                ago);
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
