package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.CaptureHold;
import com.finapp.payments.JdbcCaptureHoldStore;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentFailureReason;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * payments {@code V034} live (`P13-TSK-010`; {@code INV-LIFE-01}/{@code -02}/{@code -04}, {@code INV-RSK-02}): risk's
 * refusal on the intent - the edge {@code REQUIRES_CONFIRMATION → FAILED} only with its {@code RISK_REFUSED}, the reason
 * frozen and never on another edge, no attempt ever born for a refused intent - and slice 2's capture hold - born open
 * beside its own card attempt with its window on the database clock, resolved exactly once, {@code CAPTURE} never past
 * {@code held_until}, and the attempt's capture refused while the hold stands - bind every writer, the migrator that owns
 * the tables included. The text of the migration is {@code PaymentsMigrationTest}'s (hermetic); this is the database's
 * own word. <strong>Written and owed</strong> (C5).
 */
@Tag("database")
@DisplayName("payments V034: the risk-judged payment, live (P13-TSK-010)")
class PaymentRiskMigrationDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Clock CLOCK = Clock.systemUTC();
    private static final Money AMOUNT = Money.ofMinorUnits(20_00, CurrencyCode.of("EUR"));
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";

    private final JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore();
    private final JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
    private final JdbcCaptureHoldStore holds = new JdbcCaptureHoldStore();

    // ------------------------------------------------------------------ the refusal on the intent

    @Test
    @DisplayName("REQUIRES_CONFIRMATION -> FAILED only with RISK_REFUSED, in one statement; the reason frozen; never on"
            + " PROCESSING -> FAILED; never an attempt's reason on the intent; FAILED terminal - for every writer")
    void theRefusalNeedsItsReasonAndFreezesIt() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentIntent bare = intent(app);
            assertSqlState(RAISED, () -> update(app, "status = 'FAILED'", bare.id().value()));
            PaymentIntent refused = intent(app);
            assertThat(intents.refuse(app, refused.id(), PaymentFailureReason.RISK_REFUSED)).isTrue();
            assertThat(intents.findById(app, refused.id()).orElseThrow().failureReason())
                    .contains(PaymentFailureReason.RISK_REFUSED);
            assertThat(intents.refuse(app, refused.id(), PaymentFailureReason.RISK_REFUSED))
                    .as("the conditional's loser: no longer awaiting confirmation").isFalse();
            // The freeze fires first (the BEFORE trigger, ahead of the reason's own CHECK).
            assertSqlState(RAISED, () -> update(app, "failure_reason = 'DECLINED'", refused.id().value()));
            assertSqlState(RAISED, () -> update(app, "status = 'PROCESSING'", refused.id().value()));
            PaymentIntent dispatched = intent(app);
            assertThat(intents.transition(app, dispatched.id(), PaymentIntentStatus.REQUIRES_CONFIRMATION,
                    PaymentIntentStatus.PROCESSING)).isTrue();
            assertSqlState(RAISED, () -> update(app, "status = 'FAILED', failure_reason = 'RISK_REFUSED'",
                    dispatched.id().value()));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            PaymentIntent owned = intent(migrator);
            intents.refuse(migrator, owned.id(), PaymentFailureReason.RISK_REFUSED);
            assertSqlState(RAISED, () -> update(migrator, "failure_reason = NULL", owned.id().value()));
        }
    }

    @Test
    @DisplayName("no attempt is ever born for a refused intent - the refusal precedes every dispatch")
    void noAttemptIsBornForARefusedIntent() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentIntent refused = intent(app);
            intents.refuse(app, refused.id(), PaymentFailureReason.RISK_REFUSED);
            assertSqlState(CHECK_VIOLATION, () -> attempts.insert(app, attempt(refused)));
        }
    }

    // ------------------------------------------------------------------ the capture hold

    @Test
    @DisplayName("a hold is born open beside its own card attempt, its window the database's (held_until = held_at +"
            + " the validity), resolved exactly once with its key, its instant stamped; never born resolved")
    void aHoldIsBornOpenAndResolvesOnce() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt attempt = authorizedAttempt(app);
            holds.insert(app, attempt.intentId(), attempt.id(), Duration.ofHours(72));
            // A timestamptz difference renders "3 days", so the window is read in seconds (P13-TST-001's first run of this owed suite).
            assertThat(text(app, "SELECT extract(epoch FROM held_until - held_at)::bigint::text FROM"
                    + " payments.payment_capture_hold WHERE intent_id = ?", attempt.intentId().value())).isEqualTo("259200");
            // The born-once arbiter alone (P13-DOC-001, the review's A8-2): every trigger and foreign key suspended, a
            // copy of the hold under a fresh intent is refused by payment_capture_hold_one_per_attempt by name - one
            // hold per attempt whatever the domain or the triggers above it would have said. Rolled back.
            try (Connection bootstrap = DatabaseRoles.bootstrap()) {
                bootstrap.setAutoCommit(false);
                try (java.sql.Statement suspend = bootstrap.createStatement()) {
                    suspend.execute("SET LOCAL session_replication_role = replica");
                }
                try (PreparedStatement copy = bootstrap.prepareStatement("INSERT INTO payments.payment_capture_hold"
                        + " (intent_id, attempt_id, held_at, held_until) SELECT gen_random_uuid(), attempt_id, held_at,"
                        + " held_until FROM payments.payment_capture_hold WHERE intent_id = ?")) {
                    copy.setObject(1, attempt.intentId().value());
                    assertThatThrownBy(copy::executeUpdate).isInstanceOf(SQLException.class)
                            .hasMessageContaining("payment_capture_hold_one_per_attempt")
                            .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23505"));
                } finally {
                    bootstrap.rollback();
                }
            }
            assertThat(holds.resolveVoid(app, attempt.intentId(), "risk.action:v1")).isTrue();
            CaptureHold voided = holds.findForIntent(app, attempt.intentId()).orElseThrow();
            assertThat(voided.resolution()).contains(CaptureHold.Resolution.VOID);
            assertThat(voided.resolvedAt()).isPresent();
            assertThat(holds.resolveCapture(app, attempt.intentId(), "risk.action:c1")).isFalse();
            assertSqlState(RAISED, () -> updateHold(app, "resolution = 'CAPTURE', resolution_key = 'k'",
                    attempt.intentId().value()));

            PaymentAttempt another = authorizedAttempt(app);
            assertSqlState(CHECK_VIOLATION, () -> insertResolvedHold(app, another));
            PaymentAttempt foreign = authorizedAttempt(app);
            assertSqlState(CHECK_VIOLATION, () -> holds.insert(app, another.intentId(), foreign.id(),
                    Duration.ofHours(1)));
        }
    }

    @Test
    @DisplayName("CAPTURE never past held_until and EXPIRED never before it, on the database clock, for every writer")
    void theWindowDecidesTheResolution() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt early = authorizedAttempt(app);
            holds.insert(app, early.intentId(), early.id(), Duration.ofHours(1));
            assertThat(holds.expire(app, early.intentId())).as("before held_until").isFalse();
            assertSqlState(RAISED, () -> updateHold(app, "resolution = 'EXPIRED'", early.intentId().value()));

            PaymentAttempt late = authorizedAttempt(app);
            holds.insert(app, late.intentId(), late.id(), Duration.ofMillis(200));
            Thread.sleep(400);
            assertThat(holds.resolveCapture(app, late.intentId(), "risk.action:late")).isFalse();
            assertSqlState(RAISED, () -> updateHold(app, "resolution = 'CAPTURE', resolution_key = 'k'",
                    late.intentId().value()));
            assertThat(holds.claimExpired(app, java.util.List.of())).isPresent();
            assertThat(holds.expire(app, late.intentId())).isTrue();
        }
    }

    @Test
    @DisplayName("the capture waits for its hold: AUTHORIZED -> CAPTURE_DISPATCHED is refused while the hold is open"
            + " (the store's conditional answers false, a raw writer is refused) and admitted once released for CAPTURE")
    void theCaptureWaitsForItsHold() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            PaymentAttempt attempt = authorizedAttempt(app);
            holds.insert(app, attempt.intentId(), attempt.id(), Duration.ofHours(72));
            assertThat(attempts.dispatchCapture(app, attempt.id(), new ProviderIdempotencyReference("cap-" + IDS.next())))
                    .as("the conditional honours the hold").isFalse();
            try (Connection migrator = DatabaseRoles.migrator()) {
                assertSqlState(CHECK_VIOLATION, () -> {
                    try (PreparedStatement update = migrator.prepareStatement("UPDATE payments.payment_attempt SET"
                            + " status = 'CAPTURE_DISPATCHED', capture_reference = 'cap-raw' WHERE id = ?")) {
                        update.setObject(1, attempt.id().value());
                        update.executeUpdate();
                    }
                });
            }
            assertThat(holds.resolveCapture(app, attempt.intentId(), "risk.action:go")).isTrue();
            assertThat(attempts.dispatchCapture(app, attempt.id(), new ProviderIdempotencyReference("cap-" + IDS.next())))
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ the rows

    private PaymentIntent intent(Connection connection) {
        PaymentIntent intent = PaymentIntent.create(IDS, CLOCK, IDS.next(), IDS.next(), IDS.next(),
                LedgerAccountId.of(IDS.next()), AMOUNT);
        intents.insert(connection, intent);
        return intent;
    }

    private static PaymentAttempt attempt(PaymentIntent intent) {
        return PaymentAttempt.create(IDS, CLOCK, intent.id(), SimulatedCardPspAdapter.RAIL.id(),
                new ProviderIdempotencyReference("auth-" + IDS.next()));
    }

    /** A card attempt AUTHORIZED on a PROCESSING intent. */
    private PaymentAttempt authorizedAttempt(Connection connection) {
        PaymentIntent intent = intent(connection);
        intents.transition(connection, intent.id(), PaymentIntentStatus.REQUIRES_CONFIRMATION,
                PaymentIntentStatus.PROCESSING);
        PaymentAttempt attempt = attempt(intent);
        attempts.insert(connection, attempt);
        assertThat(attempts.authorize(connection, attempt.id(), PaymentAttemptStatus.AUTH_DISPATCHED,
                new ProviderReference("psp-" + IDS.next()), AMOUNT)).isTrue();
        return attempt;
    }

    private static void insertResolvedHold(Connection connection, PaymentAttempt attempt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO payments.payment_capture_hold"
                + " (intent_id, attempt_id, held_at, held_until, resolution, resolution_key, resolved_at) VALUES"
                + " (?, ?, statement_timestamp(), statement_timestamp() + interval '1 hour', 'VOID', 'k',"
                + " statement_timestamp())")) {
            insert.setObject(1, attempt.intentId().value());
            insert.setObject(2, attempt.id().value());
            insert.executeUpdate();
        }
    }

    private static int update(Connection connection, String set, UUID intent) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE payments.payment_intent SET " + set + " WHERE id = ?")) {
            update.setObject(1, intent);
            return update.executeUpdate();
        }
    }

    private static int updateHold(Connection connection, String set, UUID intent) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE payments.payment_capture_hold SET " + set + " WHERE intent_id = ?")) {
            update.setObject(1, intent);
            return update.executeUpdate();
        }
    }

    private static String text(Connection connection, String sql, Object argument) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    @FunctionalInterface
    private interface Statement {
        void run() throws Exception;
    }

    /** A database refusal with {@code state}: raw, or as a store reports it ({@code DatabaseFailure}'s SQLState). */
    private static void assertSqlState(String state, Statement statement) {
        assertThatThrownBy(statement::run)
                .satisfies(failure -> {
                    if (failure instanceof com.finapp.payments.PaymentsStorageException stored) {
                        assertThat(stored.getMessage()).contains("(SQLState " + state + ")");
                        return;
                    }
                    Throwable cause = failure;
                    while (cause != null && !(cause instanceof SQLException)) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).as("a database refusal, not " + failure).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
                });
    }
}
