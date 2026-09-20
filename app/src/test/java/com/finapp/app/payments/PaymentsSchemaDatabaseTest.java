package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The payments schema against a real PostgreSQL, raw SQL from scratch (`P5-TSK-008`).
 *
 * <p><strong>No store exists yet, deliberately</strong> — the commands are
 * `P5-TSK-009`/`-010`/`-015` — so everything here is prepared statements: exactly the writer
 * the schema's own layers must bind. What this suite owns is what only the database can prove:
 * the machine triggers and freezes binding the <em>migrator</em> as well as the application
 * role, the smuggled-edge probe (a payload edit hidden inside a legal edge — the only shape
 * that isolates the NULL-to-value rule from the edge check), the intent's ONE-column
 * {@code UPDATE} grant swept per column from {@code information_schema}, the one-live-attempt
 * index as the ten-way arbiter, <strong>the refund sum bound under concurrency for every
 * writer</strong> (`INV-PAY-05` at {@code DB-CONSTRAINT} rank — ten instances race and exactly
 * the budget lands), and the evidence table's append-only-for-everyone regime
 * (`INV-HIST-02`).
 */
@Tag("database")
@DisplayName("payments schema (P5-TSK-008)")
class PaymentsSchemaDatabaseTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RAISED = "P0001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    @DisplayName("the intent's machine edges and freeze bind every writer, the migrator included")
    void intentEdgesAndFreezeBindEveryWriter() throws Exception {
        UUID intent = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "REQUIRES_CONFIRMATION");
            try (PreparedStatement confirm = app.prepareStatement(
                    "UPDATE payments.payment_intent SET status = 'PROCESSING' WHERE id = ?")) {
                confirm.setObject(1, intent);
                assertThat(confirm.executeUpdate()).isEqualTo(1);
            }
            // Nothing transitions TO the birth state - at the schema as at the aggregate.
            assertSqlState(RAISED, () -> {
                try (PreparedStatement back = app.prepareStatement(
                        "UPDATE payments.payment_intent SET status = 'REQUIRES_CONFIRMATION'"
                                + " WHERE id = ?")) {
                    back.setObject(1, intent);
                    back.executeUpdate();
                }
            });
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // An illegal edge is refused for the role that owns the table (INV-LIFE-02 for
            // every writer): PROCESSING -> CANCELLED is not an edge.
            assertSqlState(RAISED, () -> {
                try (PreparedStatement cancel = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET status = 'CANCELLED'"
                                + " WHERE id = ?")) {
                    cancel.setObject(1, intent);
                    cancel.executeUpdate();
                }
            });
            // Nothing but status ever changes after birth - the freeze binds raw SQL.
            assertSqlState(RAISED, () -> {
                try (PreparedStatement tamper = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET amount_minor = amount_minor + 1"
                                + " WHERE id = ?")) {
                    tamper.setObject(1, intent);
                    tamper.executeUpdate();
                }
            });
        }
    }

    @Test
    @DisplayName("the intent's UPDATE grant is ONE column, swept from information_schema")
    void theIntentsUpdateGrantIsOneColumn() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (String column : columnsOf(app, "payment_intent")) {
                String update = "UPDATE payments.payment_intent SET " + column + " = " + column
                        + " WHERE false";
                if ("status".equals(column)) {
                    // The positive control: the one granted column plans cleanly.
                    assertThatCode(() -> {
                        try (Statement allowed = app.createStatement()) {
                            allowed.executeUpdate(update);
                        }
                    }).doesNotThrowAnyException();
                    continue;
                }
                assertSqlState(INSUFFICIENT_PRIVILEGE, () -> {
                    try (Statement denied = app.createStatement()) {
                        denied.executeUpdate(update);
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("ten concurrent attempts for one intent land one live row, and a terminal frees the slot")
    void tenConcurrentAttemptsProduceOneLiveRow() throws Exception {
        UUID intent = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
        }

        List<Callable<UUID>> races = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            races.add(() -> {
                // Own connection per simulated instance (P0-TST-009).
                try (Connection instance = DatabaseRoles.application()) {
                    UUID attempt = UUID.randomUUID();
                    insertAttempt(instance, attempt, intent, "AUTH_DISPATCHED");
                    return attempt;
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<UUID> landed = new ArrayList<>();
        try {
            for (Future<UUID> outcome : pool.invokeAll(races)) {
                try {
                    landed.add(outcome.get());
                } catch (Exception refused) {
                    assertThat(refused.getCause())
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(UNIQUE_VIOLATION);
                }
            }
        } finally {
            pool.shutdown();
        }
        assertThat(landed).hasSize(1);

        try (Connection app = DatabaseRoles.application()) {
            // The winner fails; the slot frees (the index is partial over the non-terminal
            // states) - the schema built for the N attempts Phase 7 calibrates.
            try (PreparedStatement fail = app.prepareStatement(
                    "UPDATE payments.payment_attempt SET status = 'FAILED',"
                            + " failure_reason = 'DECLINED' WHERE id = ?")) {
                fail.setObject(1, landed.get(0));
                assertThat(fail.executeUpdate()).isEqualTo(1);
            }
            assertThatCode(() ->
                            insertAttempt(app, UUID.randomUUID(), intent, "AUTH_DISPATCHED"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a payload edit smuggled inside a legal edge is refused for every writer")
    void aSmuggledPayloadEditIsRefused() throws Exception {
        UUID intent = UUID.randomUUID();
        UUID honest = UUID.randomUUID();
        UUID smuggled = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, honest, intent, "CAPTURE_DISPATCHED");

            // The honest capture: the legal edge carrying exactly its payload.
            try (PreparedStatement capture = app.prepareStatement(
                    "UPDATE payments.payment_attempt SET status = 'CAPTURED',"
                            + " capture_provider_reference = ?, captured_amount_minor = 1000,"
                            + " captured_currency = 'EUR', captured_scale = 2 WHERE id = ?")) {
                capture.setString(1, "psp-cap-" + honest);
                capture.setObject(2, honest);
                assertThat(capture.executeUpdate()).isEqualTo(1);
            }
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            UUID intent2 = UUID.randomUUID();
            insertIntent(migrator, intent2, "PROCESSING");
            insertAttempt(migrator, smuggled, intent2, "CAPTURE_DISPATCHED");

            // The same legal edge, with the authorized amount quietly rewritten inside it -
            // the only shape that isolates the NULL-to-value rule from the edge check - as
            // raw SQL from the migrator, the writer the grants cannot bind.
            assertSqlState(RAISED, () -> {
                try (PreparedStatement tampered = migrator.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = 'CAPTURED',"
                                + " capture_provider_reference = ?,"
                                + " captured_amount_minor = 1000, captured_currency = 'EUR',"
                                + " captured_scale = 2, authorized_amount_minor = 2000"
                                + " WHERE id = ?")) {
                    tampered.setString(1, "psp-cap-" + smuggled);
                    tampered.setObject(2, smuggled);
                    tampered.executeUpdate();
                }
            });
        }
    }

    @Test
    @DisplayName("the coherence CHECKs refuse every corrupt shape the constructor refuses")
    void theCoherenceChecksRefuseEveryCorruptShape() throws Exception {
        UUID intent = UUID.randomUUID();
        try (Connection migrator = DatabaseRoles.migrator()) {
            insertIntent(migrator, intent, "PROCESSING");

            // A reason on a live row / FAILED without one - both directions.
            assertCheck(migrator, intent, "AUTHORIZED", attempt -> {
                attempt.reason = "DECLINED";
            }, "payment_attempt_reason_arrives_exactly_when_failed");
            assertCheck(migrator, intent, "FAILED", attempt -> {
                attempt.reason = null;
            }, "payment_attempt_reason_arrives_exactly_when_failed");

            // The issuer's promise split in half.
            assertCheck(migrator, intent, "AUTHORIZED", attempt -> {
                attempt.authorizedMinor = null;
            }, "payment_attempt_promise_is_one_fact");

            // Pre-auth states carry nothing beyond birth.
            assertCheck(migrator, intent, "AUTH_DISPATCHED", attempt -> {
                attempt.authProviderReference = "psp-auth-early";
                attempt.authorizedMinor = 1000L;
            }, "payment_attempt_stage_facts_match_status");

            // The unreachable FAILED shape: the promise held, no capture dispatched.
            assertCheck(migrator, intent, "FAILED", attempt -> {
                attempt.authProviderReference = "psp-auth-orphan";
                attempt.authorizedMinor = 1000L;
                attempt.reason = "DECLINED";
            }, "payment_attempt_stage_facts_match_status");

            // A captured amount before CAPTURED.
            assertCheck(migrator, intent, "CAPTURE_DISPATCHED", attempt -> {
                attempt.captureProviderReference = "psp-cap-early";
                attempt.capturedMinor = 1000L;
            }, "payment_attempt_captured_pair_arrives_exactly_when_captured");

            // The stored over-capture - INV-PAY-05's row-local half for the raw writer.
            assertCheck(migrator, intent, "CAPTURED", attempt -> {
                attempt.capturedMinor = 1001L;
            }, "payment_attempt_capture_is_bounded_by_authorization");

            // A non-positive promise.
            assertCheck(migrator, intent, "AUTHORIZED", attempt -> {
                attempt.authorizedMinor = -1000L;
            }, "payment_attempt_amounts_are_positive");
        }
    }

    @Test
    @DisplayName("the refund bound refuses over-refund and the uncaptured subject, for every writer")
    void theRefundBoundRefusesForEveryWriter() throws Exception {
        UUID intent = UUID.randomUUID();
        UUID captured = UUID.randomUUID();
        UUID authorizedOnly = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, captured, intent, "CAPTURED");
            UUID intent2 = UUID.randomUUID();
            insertIntent(app, intent2, "PROCESSING");
            insertAttempt(app, authorizedOnly, intent2, "AUTHORIZED");

            // 999 then 1 - refund to the penny is legal, the bound is <=.
            insertRefund(app, UUID.randomUUID(), captured, 999, "EUR");
            insertRefund(app, UUID.randomUUID(), captured, 1, "EUR");

            // One minor unit past the capture creates money (INV-PAY-05).
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, UUID.randomUUID(), captured, 1, "EUR"));

            // Only a CAPTURED attempt has anything to return.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, UUID.randomUUID(), authorizedOnly, 1, "EUR"));

            // The capture's currency, or nothing.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, UUID.randomUUID(), captured, 1, "USD"));

            // A refund of a nonexistent attempt is the trigger's refusal before the FK's.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, UUID.randomUUID(), UUID.randomUUID(), 1, "EUR"));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The bound binds the migrator too - "for every writer" is the accept's own text.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(migrator, UUID.randomUUID(), captured, 1, "EUR"));
        }
    }

    @Test
    @DisplayName("ten concurrent partial refunds accept exactly the budget")
    void tenConcurrentPartialRefundsAcceptExactlyTheBudget() throws Exception {
        UUID intent = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED"); // captured 10.00 EUR = 1000
        }

        List<Callable<Boolean>> races = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            races.add(() -> {
                try (Connection instance = DatabaseRoles.application()) {
                    insertRefund(instance, UUID.randomUUID(), attempt, 300, "EUR");
                    return true;
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        int landed = 0;
        try {
            for (Future<Boolean> outcome : pool.invokeAll(races)) {
                try {
                    outcome.get();
                    landed++;
                } catch (Exception refused) {
                    assertThat(refused.getCause())
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(CHECK_VIOLATION);
                }
            }
        } finally {
            pool.shutdown();
        }

        // 3 x 300 fit inside 1000; a fourth would be 1200. Exactly three, whichever
        // instances won - and the table agrees with the count.
        assertThat(landed).isEqualTo(3);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement sum = app.prepareStatement(
                        "SELECT coalesce(sum(amount_minor), 0) FROM payments.refund"
                                + " WHERE attempt_id = ? AND status <> 'FAILED'")) {
            sum.setObject(1, attempt);
            try (ResultSet result = sum.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(900);
            }
        }
    }

    @Test
    @DisplayName("a FAILED refund frees its budget")
    void aFailedRefundFreesItsBudget() throws Exception {
        UUID intent = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED");

            insertRefund(app, first, attempt, 800, "EUR");
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, UUID.randomUUID(), attempt, 300, "EUR"));

            // The provider refused the first refund: DISPATCHED -> FAILED releases its budget
            // (the sum counts non-FAILED rows - only a refusal releases money possibly moving).
            try (PreparedStatement fail = app.prepareStatement(
                    "UPDATE payments.refund SET status = 'FAILED' WHERE id = ?")) {
                fail.setObject(1, first);
                assertThat(fail.executeUpdate()).isEqualTo(1);
            }
            assertThatCode(() -> insertRefund(app, UUID.randomUUID(), attempt, 300, "EUR"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("provider evidence is append-only for every writer, and its shape CHECKs hold")
    void evidenceIsAppendOnlyForEveryWriter() throws Exception {
        UUID intent = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID evidence = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "AUTH_DISPATCHED");
            insertEvidence(app, evidence, attempt, null, "RESPONSE", 10);

            // The unattributable webhook is STILL retained - both subjects NULL is legal.
            assertThatCode(() ->
                            insertEvidence(app, UUID.randomUUID(), null, null, "WEBHOOK", 10))
                    .doesNotThrowAnyException();

            // The application role holds no UPDATE and no DELETE at all.
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> {
                try (Statement update = app.createStatement()) {
                    update.executeUpdate(
                            "UPDATE payments.provider_evidence SET key_version = 2");
                }
            });
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> {
                try (Statement delete = app.createStatement()) {
                    delete.executeUpdate("DELETE FROM payments.provider_evidence");
                }
            });

            // Two subjects on one row is a claim about two operations - refused.
            assertSqlState(CHECK_VIOLATION, () -> insertEvidence(
                    app, UUID.randomUUID(), attempt, attempt, "REQUEST", 10));
            // An unknown kind, a plaintext passed off as ciphertext, a short nonce.
            assertSqlState(CHECK_VIOLATION, () -> insertEvidence(
                    app, UUID.randomUUID(), attempt, null, "SCREENSHOT", 10));
            assertSqlState(CHECK_VIOLATION, () -> insertRawEvidence(
                    app, UUID.randomUUID(), "RESPONSE", new byte[10], new byte[12], 10));
            assertSqlState(CHECK_VIOLATION, () -> insertRawEvidence(
                    app, UUID.randomUUID(), "RESPONSE", new byte[26], new byte[11], 10));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The trigger binds the role the grants cannot (INV-HIST-02: evidence that can be
            // edited is not evidence).
            assertSqlState(RAISED, () -> {
                try (Statement update = migrator.createStatement()) {
                    update.executeUpdate(
                            "UPDATE payments.provider_evidence SET key_version = 2");
                }
            });
            assertSqlState(RAISED, () -> {
                try (Statement delete = migrator.createStatement()) {
                    delete.executeUpdate("DELETE FROM payments.provider_evidence");
                }
            });
        }
    }

    @Test
    @DisplayName("the lifecycle histories are insert-only for the application role")
    void historiesAreInsertOnlyForTheApplication() throws Exception {
        UUID intent = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            try (PreparedStatement event = app.prepareStatement(
                    // The V006 actor model (P5-TSK-009): actor_id as text plus actor_type -
                    // the audit_record vocabulary, because outcome transitions are the
                    // platform's and 'system' is not a UUID.
                    "INSERT INTO payments.payment_intent_event"
                            + " (intent_id, from_status, to_status, actor_id, actor_type,"
                            + " occurred_at)"
                            + " VALUES (?, 'REQUIRES_CONFIRMATION', 'PROCESSING', ?, 'CUSTOMER',"
                            + " ?)")) {
                event.setObject(1, intent);
                event.setString(2, UUID.randomUUID().toString());
                event.setTimestamp(3, Timestamp.from(Instant.now()));
                assertThat(event.executeUpdate()).isEqualTo(1);
            }
            for (String history : List.of(
                    "payment_intent_event", "payment_attempt_event", "refund_event")) {
                assertSqlState(INSUFFICIENT_PRIVILEGE, () -> {
                    try (Statement update = app.createStatement()) {
                        update.executeUpdate("UPDATE payments." + history
                                + " SET actor_id = actor_id WHERE false");
                    }
                });
                assertSqlState(INSUFFICIENT_PRIVILEGE, () -> {
                    try (Statement delete = app.createStatement()) {
                        delete.executeUpdate("DELETE FROM payments." + history);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ fixtures and helpers

    /** The mutable shape {@link #assertCheck} corrupts before inserting. */
    private static final class AttemptRow {
        String authProviderReference;
        Long authorizedMinor;
        String captureReference;
        String captureProviderReference;
        Long capturedMinor;
        String reason;
    }

    @FunctionalInterface
    private interface Corruption {
        void applyTo(AttemptRow attempt);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

    private static void assertSqlState(String expected, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(expected);
    }

    /** Plants a corrupted attempt row and asserts the named CHECK refuses it. */
    private static void assertCheck(
            Connection connection,
            UUID intent,
            String status,
            Corruption corruption,
            String constraint) {
        AttemptRow attempt = coherentRow(status);
        corruption.applyTo(attempt);
        assertThatThrownBy(() ->
                        insertAttemptRow(connection, UUID.randomUUID(), intent, status, attempt))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    assertThat(((SQLException) failure).getSQLState())
                            .isEqualTo(CHECK_VIOLATION);
                    assertThat(failure.getMessage()).contains(constraint);
                });
    }

    /** The coherent field shape per status - the aggregate fixture's shapes, in SQL. */
    private static AttemptRow coherentRow(String status) {
        AttemptRow attempt = new AttemptRow();
        switch (status) {
            case "AUTH_DISPATCHED", "AUTH_UNKNOWN" -> { }
            case "AUTHORIZED" -> {
                attempt.authProviderReference = "psp-auth-" + UUID.randomUUID();
                attempt.authorizedMinor = 1000L;
            }
            case "CAPTURE_DISPATCHED", "CAPTURE_UNKNOWN" -> {
                attempt.authProviderReference = "psp-auth-" + UUID.randomUUID();
                attempt.authorizedMinor = 1000L;
                attempt.captureReference = "cap-" + UUID.randomUUID();
            }
            case "CAPTURED" -> {
                attempt.authProviderReference = "psp-auth-" + UUID.randomUUID();
                attempt.authorizedMinor = 1000L;
                attempt.captureReference = "cap-" + UUID.randomUUID();
                attempt.captureProviderReference = "psp-cap-" + UUID.randomUUID();
                attempt.capturedMinor = 1000L;
            }
            case "FAILED" -> attempt.reason = "DECLINED";
            default -> throw new IllegalArgumentException(status);
        }
        return attempt;
    }

    private static void insertIntent(Connection connection, UUID id, String status)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " payment_method_id, wallet_account_id, amount_minor, currency,"
                        + " scale, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, UUID.randomUUID());
            insert.setObject(3, UUID.randomUUID());
            insert.setObject(4, UUID.randomUUID());
            insert.setObject(5, UUID.randomUUID());
            insert.setString(6, status);
            insert.setTimestamp(7, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    private static void insertAttempt(
            Connection connection, UUID id, UUID intent, String status) throws SQLException {
        insertAttemptRow(connection, id, intent, status, coherentRow(status));
    }

    private static void insertAttemptRow(
            Connection connection, UUID id, UUID intent, String status, AttemptRow attempt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                        + " capture_reference, auth_provider_reference,"
                        + " capture_provider_reference, authorized_amount_minor,"
                        + " authorized_currency, authorized_scale, captured_amount_minor,"
                        + " captured_currency, captured_scale, failure_reason, status,"
                        + " created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, intent);
            insert.setString(3, "auth-" + id);
            insert.setString(4, attempt.captureReference);
            insert.setString(5, attempt.authProviderReference);
            insert.setString(6, attempt.captureProviderReference);
            insert.setObject(7, attempt.authorizedMinor);
            insert.setString(8, attempt.authorizedMinor == null ? null : "EUR");
            insert.setObject(9, attempt.authorizedMinor == null ? null : (short) 2);
            insert.setObject(10, attempt.capturedMinor);
            insert.setString(11, attempt.capturedMinor == null ? null : "EUR");
            insert.setObject(12, attempt.capturedMinor == null ? null : (short) 2);
            insert.setString(13, attempt.reason);
            insert.setString(14, status);
            insert.setTimestamp(15, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    private static void insertRefund(
            Connection connection, UUID id, UUID attempt, long amountMinor, String currency)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.refund (id, attempt_id, amount_minor, currency, scale,"
                        + " reason, hold_reference, provider_idempotency_reference,"
                        + " provider_reference, status, created_at)"
                        + " VALUES (?, ?, ?, ?, 2, 'operator-recorded reason', ?, ?, NULL,"
                        + " 'DISPATCHED', ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, attempt);
            insert.setLong(3, amountMinor);
            insert.setString(4, currency);
            insert.setObject(5, UUID.randomUUID());
            insert.setString(6, "refund-" + id);
            insert.setTimestamp(7, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    private static void insertEvidence(
            Connection connection,
            UUID id,
            UUID attempt,
            UUID refund,
            String kind,
            int contentLength)
            throws SQLException {
        insertSubjectEvidence(connection, id, attempt, refund, kind,
                new byte[contentLength + 16], new byte[12], contentLength);
    }

    private static void insertRawEvidence(
            Connection connection,
            UUID id,
            String kind,
            byte[] ciphertext,
            byte[] nonce,
            int contentLength)
            throws SQLException {
        insertSubjectEvidence(connection, id, null, null, kind, ciphertext, nonce,
                contentLength);
    }

    private static void insertSubjectEvidence(
            Connection connection,
            UUID id,
            UUID attempt,
            UUID refund,
            String kind,
            byte[] ciphertext,
            byte[] nonce,
            int contentLength)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.provider_evidence (id, attempt_id, refund_id, kind,"
                        + " content_ciphertext, content_nonce, key_version, checksum_sha256,"
                        + " content_length, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, attempt);
            insert.setObject(3, refund);
            insert.setString(4, kind);
            insert.setBytes(5, ciphertext);
            insert.setBytes(6, nonce);
            insert.setBytes(7, new byte[32]);
            insert.setInt(8, contentLength);
            insert.setTimestamp(9, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    private static List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'payments' AND table_name = ?"
                        + " ORDER BY ordinal_position")) {
            query.setString(1, table);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
        }
        assertThat(columns).isNotEmpty();
        return columns;
    }
}
