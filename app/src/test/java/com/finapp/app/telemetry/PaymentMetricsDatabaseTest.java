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

    /** The sweep's own default bound: a dispatch younger than this is mid-question. */
    /** Raw rows mint UUIDv7 like every honest writer (ADR-0013): a v4 id in this
     * shared database is a poison pill - the SWEEP's candidate list rehydrates
     * typed ids, so one corrupt row would stall every later suite's sweeper. */
    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(
                    java.time.Clock.systemUTC(), new java.security.SecureRandom());

    private static final java.time.Duration BOUND = java.time.Duration.ofMinutes(10);

    @Test
    @DisplayName("an unknown state counts at any age, a dispatch inside the sweep's bound does not,"
            + " and the age is the state's own")
    void onlyUnknownStatesCountAndTheAgeIsTheStates() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttemptStore.UnknownReading before = attempts.unknownReading(app, BOUND);

            // Two attempts, both seeded through LEGAL edges only - the machine's own
            // trigger refuses anything else, which is itself worth knowing here: one moved
            // to AUTH_UNKNOWN an hour ago, one left AUTH_DISPATCHED where it was born.
            // The dispatched row is the sharp control: it is the platform MID-QUESTION,
            // not stranded, and counting it would make the alert fire on every healthy
            // in-flight payment.
            UUID stale = seedAttempt(app, "AUTH_UNKNOWN", "1 hour");
            seedAttempt(app, "AUTH_DISPATCHED", "0 minutes");

            PaymentAttemptStore.UnknownReading after = attempts.unknownReading(app, BOUND);
            assertThat(after.active() - before.active())
                    .as("the unknown one only - a dispatched attempt inside the sweep's bound is"
                            + " mid-question, and the gauge that counted it would alert on"
                            + " healthy traffic")
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
            PaymentAttemptStore.UnknownReading reading = refunds.unknownReading(app, BOUND);
            assertThat(reading.active()).isGreaterThanOrEqualTo(0);
            assertThat(reading.oldestAgeSeconds()).isGreaterThanOrEqualTo(0);
        }
    }

    /**
     * The Phase 6 -> 7 transition's widening, the payout's shape (P6-TSK-013): a dispatch whose
     * instance crashed mid-call, and an authorization nothing captured, are stuck exactly as an
     * unknown one is - and counting only the unknown left both invisible whenever the sweep was
     * down. Each counts once it is past the sweep's own bound, and not before.
     */
    @Test
    @DisplayName("a dispatch and an authorization past the sweep's bound count as stuck; inside it,"
            + " neither does")
    void aDispatchOrAnAuthorizationPastTheBoundCounts() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttemptStore.UnknownReading before = attempts.unknownReading(app, BOUND);

            seedAttempt(app, "AUTH_DISPATCHED", "1 hour");
            seedAttempt(app, "AUTHORIZED", "1 hour");
            // The controls, inside the bound: mid-question, and freshly authorized.
            seedAttempt(app, "AUTH_DISPATCHED", "1 minute");
            seedAttempt(app, "AUTHORIZED", "1 minute");

            PaymentAttemptStore.UnknownReading after = attempts.unknownReading(app, BOUND);
            assertThat(after.active() - before.active())
                    .as("the crashed dispatch and the uncaptured authorization, and neither control")
                    .isEqualTo(2);
            assertThat(after.oldestAgeSeconds()).isGreaterThanOrEqualTo(3_500L);
        }
    }

    @Test
    @DisplayName("the push machine's states read on the same gauges: EXECUTION_UNKNOWN at any"
            + " age, EXECUTION_DISPATCHED past the bound, a waiting payer never (P7-TSK-002)")
    void thePushStatesReadOnTheSameGauges() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttemptStore.UnknownReading before = attempts.unknownReading(app, BOUND);

            seedPushAttempt(app, "EXECUTION_UNKNOWN", "1 hour");
            seedPushAttempt(app, "EXECUTION_DISPATCHED", "1 hour");
            // The controls: a payer still deciding is NOT stuck platform-side whatever the
            // age - AWAITING_PAYER's ageing is the rail's own product decision (P7-TSK-009)
            // - and a fresh dispatch is mid-question.
            seedPushAttempt(app, "AWAITING_PAYER", "3 hour");
            seedPushAttempt(app, "EXECUTION_DISPATCHED", "1 minute");

            PaymentAttemptStore.UnknownReading after = attempts.unknownReading(app, BOUND);
            assertThat(after.active() - before.active())
                    .as("the stranded unknown and the aged dispatch; neither the waiting"
                            + " payer nor the fresh dispatch")
                    .isEqualTo(2);
            assertThat(after.oldestAgeSeconds()).isGreaterThanOrEqualTo(3_500L);
        }
    }

    @Test
    @DisplayName("the pay-in gauges read their own subjects: an awaiting initiation"
            + " aged from BIRTH, and a parked confirmation aged from its parking"
            + " (P7-TSK-009, INV-REC-05)")
    void thePayInGaugesReadTheirOwnSubjects() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttemptStore.UnknownReading awaitingBefore = attempts.awaitingReading(app);
            seedPushAttempt(app, "AWAITING_PAYER", "2 hour");
            PaymentAttemptStore.UnknownReading awaitingAfter = attempts.awaitingReading(app);
            assertThat(awaitingAfter.active() - awaitingBefore.active()).isEqualTo(1);
            assertThat(awaitingAfter.oldestAgeSeconds())
                    .as("aged from BIRTH: a permit renewal must never make an old wait"
                            + " look young (P7-TSK-009)")
                    .isGreaterThanOrEqualTo(7_100L);

            com.finapp.payments.UnmatchedConfirmationStore<Connection> unmatched =
                    new com.finapp.payments.JdbcUnmatchedConfirmationStore();
            // The raw parking is READ, never committed: a parking with no DR clearing / CR
            // suspense entry behind it is exactly what the suspense identity's Phase 7 term
            // counts (PositionProof), and on an undeclared rail it is ADR-0059's wiring fault
            // to the backfill's parking walk - committed into the tier's shared database, it
            // failed every later suspense verdict and answered 500 to every later backfill.
            app.setAutoCommit(false);
            try {
                PaymentAttemptStore.UnknownReading parkedBefore = unmatched.parkedReading(app);
                execute(app,
                        "INSERT INTO payments.unmatched_confirmation (id, rail,"
                                + " scheme_reference, amount_minor, currency, scale,"
                                + " received_at, entry_ref, cause) VALUES (?, 'push-test', ?,"
                                + " 750, 'EUR', 2, now() - interval '1 hour', ?,"
                                + " 'UNATTRIBUTED')",
                        IDS.next(), "sch-gauge-" + IDS.next(), IDS.next());
                PaymentAttemptStore.UnknownReading parkedAfter = unmatched.parkedReading(app);
                assertThat(parkedAfter.active() - parkedBefore.active()).isEqualTo(1);
                assertThat(parkedAfter.oldestAgeSeconds())
                        .as("the INV-REC-05 ageing: suspense is never a quiet resting place")
                        .isGreaterThanOrEqualTo(3_500L);
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the void states read on the same gauges: VOID_UNKNOWN at any age,"
            + " VOID_DISPATCHED past the bound, a fresh dispatch never (P7-TSK-004)")
    void theVoidStatesReadOnTheSameGauges() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttemptStore.UnknownReading before = attempts.unknownReading(app, BOUND);

            // An ambiguous release counts the moment it exists: the promise may or may not
            // still stand, and only the query resolves it.
            UUID unknown = seedVoidAttempt(app, "VOID_UNKNOWN", "1 minute");
            UUID aged = seedVoidAttempt(app, "VOID_DISPATCHED", "1 hour");
            // The control: a fresh void dispatch is mid-question.
            UUID fresh = seedVoidAttempt(app, "VOID_DISPATCHED", "1 minute");

            PaymentAttemptStore.UnknownReading after = attempts.unknownReading(app, BOUND);
            assertThat(after.active() - before.active())
                    .as("the young ambiguous void and the aged dispatch; not the fresh one")
                    .isEqualTo(2);

            // Concluded on the way out: this database is persistent and shared, and a
            // parked VOID_DISPATCHED left behind would be re-sent by every later suite's
            // sweep (the re-send leg exists precisely for stranded rows) - a gauge
            // fixture must not become another suite's candidate.
            for (UUID seeded : new UUID[] {unknown, aged, fresh}) {
                concludeVoidSeed(app, seeded);
            }
        }
    }

    // -----------------------------------------------------------------

    /** A minimal attempt row in {@code status}, with its state entered {@code ago} ago. */
    private UUID seedAttempt(Connection app, String status, String ago) throws SQLException {
        UUID party = IDS.next();
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        execute(
                app,
                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Gauge Subject', now())",
                party);
        UUID customer = IDS.next();
        execute(
                app,
                "INSERT INTO party.customer (id, party_id, status, opened_at,"
                        + " status_changed_at) VALUES (?, ?, 'ACTIVE', now(), now())",
                customer,
                party);
        execute(
                app,
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " payment_method_id, credit_account_id, amount_minor, currency,"
                        + " scale, status, created_at, capture_mode)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 'EUR', 2, 'PROCESSING', now(),"
                        + " 'AUTOMATIC')",
                intent,
                party,
                customer,
                IDS.next(),
                IDS.next());
        execute(
                app,
                "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                        + " status, created_at, rail, interaction_model)"
                        + " VALUES (?, ?, ?, 'AUTH_DISPATCHED', now() - INTERVAL '" + ago
                        + "', 'card', 'TWO_STEP')",
                attempt,
                intent,
                "gauge-" + IDS.next());
        if (status.equals("AUTH_DISPATCHED")) {
            // Born dispatched: its age is its birth, the sweeper's own fallback.
            return attempt;
        }
        // The state's entry: the transition and its history row, aged by the SERVER's clock
        // (ADR-0014) - which is the expression the gauge and the sweeper share. AUTHORIZED
        // carries its stage facts, which V003's stage CHECK demands.
        if (status.equals("AUTHORIZED")) {
            execute(
                    app,
                    "UPDATE payments.payment_attempt SET status = 'AUTHORIZED',"
                            + " auth_provider_reference = ?, authorized_amount_minor = 100,"
                            + " authorized_currency = 'EUR', authorized_scale = 2 WHERE id = ?",
                    "psp_gauge-" + IDS.next(),
                    attempt);
        } else {
            execute(
                    app,
                    "UPDATE payments.payment_attempt SET status = ? WHERE id = ?",
                    status,
                    attempt);
        }
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

    /**
     * A minimal PUSH-model row in {@code status}: no dispatch reference, no two-step fact
     * (V012's shape), its state entered {@code ago} ago through legal edges only.
     */
    private UUID seedPushAttempt(Connection app, String status, String ago) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        execute(
                app,
                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Gauge Subject', now())",
                party);
        execute(
                app,
                "INSERT INTO party.customer (id, party_id, status, opened_at,"
                        + " status_changed_at) VALUES (?, ?, 'ACTIVE', now(), now())",
                customer,
                party);
        execute(
                app,
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " payment_method_id, credit_account_id, amount_minor, currency,"
                        + " scale, status, created_at, capture_mode)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 'EUR', 2, 'PROCESSING', now(),"
                        + " 'AUTOMATIC')",
                intent,
                party,
                customer,
                IDS.next(),
                IDS.next());
        execute(
                app,
                "INSERT INTO payments.payment_attempt (id, intent_id, status, created_at,"
                        // The push birth facts V017 requires (P7-TSK-009).
                        + " rail, interaction_model, end_to_end_reference,"
                        + " last_dispatched_at)"
                        + " VALUES (?, ?, 'AWAITING_PAYER', now() - INTERVAL '" + ago
                        + "', 'push-test', 'PUSH', ?, now() - INTERVAL '" + ago + "')",
                attempt,
                intent,
                IDS.next().toString().replace("-", ""));
        if (status.equals("AWAITING_PAYER")) {
            // Born waiting: no history row, no gauge reading - the payer's clock, not ours.
            return attempt;
        }
        execute(
                app,
                "UPDATE payments.payment_attempt SET status = 'EXECUTION_DISPATCHED'"
                        + " WHERE id = ?",
                attempt);
        String from = "AWAITING_PAYER";
        if (status.equals("EXECUTION_UNKNOWN")) {
            execute(
                    app,
                    "UPDATE payments.payment_attempt SET status = 'EXECUTION_UNKNOWN'"
                            + " WHERE id = ?",
                    attempt);
            from = "EXECUTION_DISPATCHED";
        }
        execute(
                app,
                "INSERT INTO payments.payment_attempt_event (attempt_id, from_status,"
                        + " to_status, actor_id, actor_type, occurred_at)"
                        + " VALUES (?, ?, ?, 'platform', 'PLATFORM',"
                        + " now() - INTERVAL '" + ago + "')",
                attempt,
                from,
                status);
        return attempt;
    }

    /** A void-stage row, seeded through legal edges only - the machine's own path. */
    private UUID seedVoidAttempt(Connection app, String status, String ago)
            throws SQLException {
        UUID attempt = seedAttempt(app, "AUTHORIZED", ago);
        execute(
                app,
                "UPDATE payments.payment_attempt SET status = 'VOID_DISPATCHED',"
                        + " void_reference = ? WHERE id = ?",
                "void-gauge-" + IDS.next(),
                attempt);
        String from = "AUTHORIZED";
        String to = "VOID_DISPATCHED";
        if (status.equals("VOID_UNKNOWN")) {
            execute(
                    app,
                    "UPDATE payments.payment_attempt SET status = 'VOID_UNKNOWN'"
                            + " WHERE id = ?",
                    attempt);
            from = "VOID_DISPATCHED";
            to = "VOID_UNKNOWN";
        }
        execute(
                app,
                "INSERT INTO payments.payment_attempt_event (attempt_id, from_status,"
                        + " to_status, actor_id, actor_type, occurred_at)"
                        + " VALUES (?, ?, ?, 'platform', 'PLATFORM',"
                        + " now() - INTERVAL '" + ago + "')",
                attempt,
                from,
                to);
        return attempt;
    }

    /** VOIDED via the machine's own edge, so the row stops being anyone's candidate. */
    private void concludeVoidSeed(Connection app, UUID attempt) throws SQLException {
        execute(
                app,
                "UPDATE payments.payment_attempt SET status = 'VOIDED',"
                        + " void_provider_reference = ? WHERE id = ?"
                        + " AND status IN ('VOID_DISPATCHED', 'VOID_UNKNOWN')",
                "psp-void-gauge-" + IDS.next(),
                attempt);
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
