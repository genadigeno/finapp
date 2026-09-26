package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.InteractionModel;
import com.finapp.payments.PaymentAttemptStatus;
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

    /** Raw rows mint UUIDv7 like every honest writer (ADR-0013): a v4 id in this
     * shared database is a poison pill - the SWEEP's candidate list rehydrates
     * typed ids, so one corrupt row would stall every later suite's sweeper. */
    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(
                    java.time.Clock.systemUTC(), new java.security.SecureRandom());

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RAISED = "P0001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    @DisplayName("the intent's machine edges and freeze bind every writer, the migrator included")
    void intentEdgesAndFreezeBindEveryWriter() throws Exception {
        UUID intent = IDS.next();
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
        UUID intent = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
        }

        List<Callable<UUID>> races = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            races.add(() -> {
                // Own connection per simulated instance (P0-TST-009).
                try (Connection instance = DatabaseRoles.application()) {
                    UUID attempt = IDS.next();
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
                            insertAttempt(app, IDS.next(), intent, "AUTH_DISPATCHED"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a payload edit smuggled inside a legal edge is refused for every writer")
    void aSmuggledPayloadEditIsRefused() throws Exception {
        UUID intent = IDS.next();
        UUID honest = IDS.next();
        UUID smuggled = IDS.next();
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
            UUID intent2 = IDS.next();
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
    @DisplayName("the rail is frozen among the birth facts for every writer, the migrator"
            + " included (P7-TSK-001, ADR-0059)")
    void theRailIsFrozenForEveryWriter() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "AUTH_DISPATCHED");
        }
        // The trigger is the rank that binds EVERY writer, so it is proven on the migrator,
        // which no grant can bind (the intentEdgesAndFreeze idiom) - once smuggled inside a
        // legal edge, once as a bare rewrite.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertSqlState(RAISED, () -> {
                try (PreparedStatement smuggled = migrator.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = 'AUTH_UNKNOWN',"
                                + " rail = 'switched' WHERE id = ?")) {
                    smuggled.setObject(1, attempt);
                    smuggled.executeUpdate();
                }
            });
            assertSqlState(RAISED, () -> {
                try (PreparedStatement rewrite = migrator.prepareStatement(
                        "UPDATE payments.payment_attempt SET rail = 'switched'"
                                + " WHERE id = ?")) {
                    rewrite.setObject(1, attempt);
                    rewrite.executeUpdate();
                }
            });
        }
    }

    @Test
    @DisplayName("the three machines bind every writer edge by edge - the exhaustive raw"
            + " sweep, expectations derived from the enum (P7-TSK-002, ADR-0059)")
    void theMachinesBindEveryWriterEdgeByEdge() throws Exception {
        // The migrator is the writer no grant can bind, so the trigger is the rank on trial.
        // For EACH model, every (from, to) pair of the FULL eleven-state vocabulary runs
        // against a fresh row per pair, and exactly the model's own edges succeed - the
        // DB-rank reconciliation of InteractionModel.edges(), the same machine that generated
        // the trigger's disjunction. Cross-model moves are inside the cross-product (a
        // two-step row asked to enter the push machine, and the reverse); the book loop is
        // the no-edges proof: born terminal, every move refused. A LEGAL move carries its
        // target's payload (the trigger raises before any CHECK, so illegal moves need none).
        try (Connection migrator = DatabaseRoles.migrator()) {
            for (InteractionModel model : InteractionModel.values()) {
                for (PaymentAttemptStatus from : model.statuses()) {
                    for (PaymentAttemptStatus to : PaymentAttemptStatus.values()) {
                        UUID intent = IDS.next();
                        UUID attempt = IDS.next();
                        insertIntent(migrator, intent, "PROCESSING");
                        if (model == InteractionModel.TWO_STEP) {
                            insertAttempt(migrator, attempt, intent, from.name());
                        } else {
                            insertForeignModelRow(migrator, attempt, intent, model,
                                    from.name());
                        }
                        boolean legal = model.permits(from, to);
                        String move = "UPDATE payments.payment_attempt SET status = ?"
                                + (legal ? legalMovePayload(to) : "")
                                + " WHERE id = ?";
                        if (legal) {
                            try (PreparedStatement update = migrator.prepareStatement(move)) {
                                update.setString(1, to.name());
                                update.setObject(2, attempt);
                                assertThat(update.executeUpdate())
                                        .as("%s: %s -> %s is the machine's own edge",
                                                model, from, to)
                                        .isEqualTo(1);
                            }
                        } else {
                            assertSqlState(RAISED, () -> {
                                try (PreparedStatement update =
                                        migrator.prepareStatement(move)) {
                                    update.setString(1, to.name());
                                    update.setObject(2, attempt);
                                    update.executeUpdate();
                                }
                            });
                        }
                    }
                }
            }
        }
    }

    /** The payload a LEGAL move into {@code to} must carry - the smuggle test's knowledge. */
    private static String legalMovePayload(PaymentAttemptStatus to) {
        return switch (to) {
            case AUTHORIZED -> ", auth_provider_reference = 'psp-swp-" + IDS.next()
                    + "', authorized_amount_minor = 1000, authorized_currency = 'EUR',"
                    + " authorized_scale = 2";
            case CAPTURE_DISPATCHED ->
                    ", capture_reference = 'cap-swp-" + IDS.next() + "'";
            case CAPTURED -> ", capture_provider_reference = 'psp-cap-swp-" + IDS.next()
                    + "', captured_amount_minor = 1000, captured_currency = 'EUR',"
                    + " captured_scale = 2";
            case FAILED -> ", failure_reason = 'DECLINED'";
            default -> "";
        };
    }

    @Test
    @DisplayName("vocabulary and model bind each other on the row, and the dispatch reference"
            + " is exactly the two-step birth fact (V012)")
    void theModelBindsVocabularyAndBirthFacts() throws Exception {
        UUID intent = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            // A two-step row in a push state: only the model CHECK stands against this shape
            // (the stage CASE has no arm for a foreign status), so the refusal names it.
            assertThatThrownBy(() -> insertAttemptRow(app, IDS.next(), intent,
                            "AWAITING_PAYER", coherentRow("AUTH_DISPATCHED")))
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> {
                        assertThat(((SQLException) failure).getSQLState())
                                .isEqualTo(CHECK_VIOLATION);
                        assertThat(failure.getMessage())
                                .contains("payment_attempt_status_matches_model");
                    });
            // A push row in a two-step state: refused too (the model CHECK and the stage
            // CHECK both stand against it, so only the state is asserted).
            assertSqlState(CHECK_VIOLATION, () -> insertForeignModelRow(
                    app, IDS.next(), intent, InteractionModel.PUSH, "AUTHORIZED"));
            // A model outside the enum's vocabulary (the model CHECK and the model-status
            // CHECK both stand against it).
            assertSqlState(CHECK_VIOLATION, () -> {
                try (PreparedStatement unknown = app.prepareStatement(
                        "INSERT INTO payments.payment_attempt (id, intent_id, status,"
                                + " created_at, rail, interaction_model)"
                                + " VALUES (?, ?, 'AWAITING_PAYER', now(), 'push-test',"
                                + " 'PULL')")) {
                    unknown.setObject(1, IDS.next());
                    unknown.setObject(2, intent);
                    unknown.executeUpdate();
                }
            });
            // The dispatch reference on a push row: ADR-0046's birth fact belongs to the
            // two-step model alone, and the CHECK holds both directions by name.
            assertThatThrownBy(() -> {
                try (PreparedStatement smuggled = app.prepareStatement(
                        "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                                + " status, created_at, rail, interaction_model)"
                                + " VALUES (?, ?, ?, 'AWAITING_PAYER', now(), 'push-test',"
                                + " 'PUSH')")) {
                    smuggled.setObject(1, IDS.next());
                    smuggled.setObject(2, intent);
                    smuggled.setString(3, "auth-smuggled-" + IDS.next());
                    smuggled.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> {
                        assertThat(((SQLException) failure).getSQLState())
                                .isEqualTo(CHECK_VIOLATION);
                        assertThat(failure.getMessage()).contains(
                                "payment_attempt_two_step_carries_its_dispatch_reference");
                    });
            // ...and a two-step row without it, the other direction of the same CHECK.
            assertThatThrownBy(() -> {
                try (PreparedStatement bare = app.prepareStatement(
                        "INSERT INTO payments.payment_attempt (id, intent_id, status,"
                                + " created_at, rail, interaction_model)"
                                + " VALUES (?, ?, 'AUTH_DISPATCHED', now(), 'card',"
                                + " 'TWO_STEP')")) {
                    bare.setObject(1, IDS.next());
                    bare.setObject(2, intent);
                    bare.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> {
                        assertThat(((SQLException) failure).getSQLState())
                                .isEqualTo(CHECK_VIOLATION);
                        assertThat(failure.getMessage()).contains(
                                "payment_attempt_two_step_carries_its_dispatch_reference");
                    });
            // No two-step fact rides a foreign row: the issuer's promise on a push row is
            // refused by the closing CHECK, by name (the pair itself coherent, so nothing
            // else stands against the shape).
            assertThatThrownBy(() -> {
                try (PreparedStatement promise = app.prepareStatement(
                        "INSERT INTO payments.payment_attempt (id, intent_id, status,"
                                + " auth_provider_reference, authorized_amount_minor,"
                                + " authorized_currency, authorized_scale, created_at, rail,"
                                + " interaction_model)"
                                + " VALUES (?, ?, 'AWAITING_PAYER', ?, 1000, 'EUR', 2, now(),"
                                + " 'push-test', 'PUSH')")) {
                    promise.setObject(1, IDS.next());
                    promise.setObject(2, intent);
                    promise.setString(3, "psp-auth-" + IDS.next());
                    promise.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> {
                        assertThat(((SQLException) failure).getSQLState())
                                .isEqualTo(CHECK_VIOLATION);
                        assertThat(failure.getMessage()).contains(
                                "payment_attempt_foreign_model_carries_no_two_step_facts");
                    });
        }
    }

    @Test
    @DisplayName("EXECUTED frees the one-live-per-intent slot exactly as the other terminals"
            + " do (V012's regenerated predicate)")
    void executedFreesTheOneLiveSlot() throws Exception {
        UUID intent = IDS.next();
        UUID running = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertForeignModelRow(app, running, intent, InteractionModel.PUSH,
                    "EXECUTION_DISPATCHED");
            // The slot is held while the execution is in flight...
            assertSqlState(UNIQUE_VIOLATION, () -> insertForeignModelRow(
                    app, IDS.next(), intent, InteractionModel.PUSH, "AWAITING_PAYER"));
            // ...and EXECUTED frees it: the third terminal is IN the generated predicate. A
            // hand-list that missed it would hold the intent's slot forever.
            try (PreparedStatement execute = app.prepareStatement(
                    "UPDATE payments.payment_attempt SET status = 'EXECUTED'"
                            + " WHERE id = ?")) {
                execute.setObject(1, running);
                assertThat(execute.executeUpdate()).isEqualTo(1);
            }
            assertThatCode(() -> insertForeignModelRow(app, IDS.next(), intent,
                            InteractionModel.PUSH, "AWAITING_PAYER"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the interaction model and the capture mode are frozen among the birth facts"
            + " for every writer, the migrator included (V012)")
    void theModelAndCaptureModeAreFrozenForEveryWriter() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "AUTH_DISPATCHED");
        }
        // The rail-freeze idiom, extended to the two facts this task births: bare rewrites
        // and rewrites smuggled inside a legal edge, both refused for the migrator.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertSqlState(RAISED, () -> {
                try (PreparedStatement rewrite = migrator.prepareStatement(
                        "UPDATE payments.payment_attempt SET interaction_model = 'PUSH'"
                                + " WHERE id = ?")) {
                    rewrite.setObject(1, attempt);
                    rewrite.executeUpdate();
                }
            });
            assertSqlState(RAISED, () -> {
                try (PreparedStatement smuggled = migrator.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = 'AUTH_UNKNOWN',"
                                + " interaction_model = 'PUSH' WHERE id = ?")) {
                    smuggled.setObject(1, attempt);
                    smuggled.executeUpdate();
                }
            });
            assertSqlState(RAISED, () -> {
                try (PreparedStatement rewrite = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET capture_mode = 'MANUAL'"
                                + " WHERE id = ?")) {
                    rewrite.setObject(1, intent);
                    rewrite.executeUpdate();
                }
            });
            assertSqlState(RAISED, () -> {
                try (PreparedStatement smuggled = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET status = 'SUCCEEDED',"
                                + " capture_mode = 'MANUAL' WHERE id = ?")) {
                    smuggled.setObject(1, intent);
                    smuggled.executeUpdate();
                }
            });
        }
    }

    @Test
    @DisplayName("the coherence CHECKs refuse every corrupt shape the constructor refuses")
    void theCoherenceChecksRefuseEveryCorruptShape() throws Exception {
        UUID intent = IDS.next();
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
        UUID intent = IDS.next();
        UUID captured = IDS.next();
        UUID authorizedOnly = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, captured, intent, "CAPTURED");
            UUID intent2 = IDS.next();
            insertIntent(app, intent2, "PROCESSING");
            insertAttempt(app, authorizedOnly, intent2, "AUTHORIZED");

            // 999 then 1 - refund to the penny is legal, the bound is <=.
            insertRefund(app, IDS.next(), captured, 999, "EUR");
            insertRefund(app, IDS.next(), captured, 1, "EUR");

            // One minor unit past the capture creates money (INV-PAY-05).
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), captured, 1, "EUR"));

            // Only a CAPTURED attempt has anything to return.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), authorizedOnly, 1, "EUR"));

            // The capture's currency, or nothing.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), captured, 1, "USD"));

            // A refund of a nonexistent attempt is the trigger's refusal before the FK's.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), IDS.next(), 1, "EUR"));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The bound binds the migrator too - "for every writer" is the accept's own text.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(migrator, IDS.next(), captured, 1, "EUR"));
        }
    }

    @Test
    @DisplayName("ten concurrent partial refunds accept exactly the budget")
    void tenConcurrentPartialRefundsAcceptExactlyTheBudget() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED"); // captured 10.00 EUR = 1000
        }

        List<Callable<Boolean>> races = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            races.add(() -> {
                try (Connection instance = DatabaseRoles.application()) {
                    insertRefund(instance, IDS.next(), attempt, 300, "EUR");
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
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID first = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED");

            insertRefund(app, first, attempt, 800, "EUR");
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), attempt, 300, "EUR"));

            // The provider refused the first refund: DISPATCHED -> FAILED releases its budget
            // (the sum counts non-FAILED rows - only a refusal releases money possibly moving).
            try (PreparedStatement fail = app.prepareStatement(
                    "UPDATE payments.refund SET status = 'FAILED' WHERE id = ?")) {
                fail.setObject(1, first);
                assertThat(fail.executeUpdate()).isEqualTo(1);
            }
            assertThatCode(() -> insertRefund(app, IDS.next(), attempt, 300, "EUR"))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * V009's clauses against raw SQL (the Phase 6 -> 7 transition): the permit is the one column
     * a status-unchanged update may move, forward only and only while the refund is resolvable,
     * and the replaced function still freezes the recorded dispatch - its key now included.
     */
    @Test
    @DisplayName("a refund's send permit moves forward only and never on a resolved refund, and"
            + " the dispatch it records stays frozen - for every writer (V009)")
    void theRefundSendPermitIsForwardOnlyAndOnlyWhileResolvable() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID refund = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED");
            insertRefund(app, refund, attempt, 500, "EUR");

            // Forward while DISPATCHED: the renewal a takeover commits before its re-send.
            assertThat(
                            updated(
                                    app,
                                    "UPDATE payments.refund SET last_dispatched_at ="
                                            + " last_dispatched_at + interval '1 minute'"
                                            + " WHERE id = ?",
                                    refund))
                    .isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    updated(
                                            app,
                                            "UPDATE payments.refund SET last_dispatched_at ="
                                                    + " last_dispatched_at - interval '30 seconds'"
                                                    + " WHERE id = ?",
                                            refund))
                    .hasMessageContaining("send permit only moves forward");
        }
        // The owner, which no column grant restrains, still meets the freeze - the key included,
        // which V004's function left out.
        try (Connection owner = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    updated(
                                            owner,
                                            "UPDATE payments.refund SET dispatch_key = 'moved'"
                                                    + " WHERE id = ?",
                                            refund))
                    .hasMessageContaining("immutable outside its outcome");
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(
                            updated(
                                    app,
                                    "UPDATE payments.refund SET status = 'COMPLETED',"
                                            + " provider_reference = 'psp-permit-1' WHERE id = ?",
                                    refund))
                    .isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    updated(
                                            app,
                                            "UPDATE payments.refund SET last_dispatched_at ="
                                                    + " last_dispatched_at + interval '1 minute'"
                                                    + " WHERE id = ?",
                                            refund))
                    .hasMessageContaining("resolved refund is never sent again");
        }
    }

    private static int updated(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setObject(1, id);
            return update.executeUpdate();
        }
    }

    @Test
    @DisplayName("RESERVED IS NOT RETURNED: the COMPLETED sum and the non-failed sum are"
            + " DIFFERENT questions, and pricing a fee against the wrong one returns money"
            + " for a refund that has not happened (P6-TSK-014)")
    void theCompletedSumIsNotTheBudgetSum() throws Exception {
        // UUIDv7 for the attempt, because the typed identifier refuses anything else
        // (ADR-0013) - this suite's other tests only ever hand raw UUIDs to SQL.
        com.finapp.sharedkernel.id.IdGenerator ids =
                new com.finapp.sharedkernel.id.IdGenerator(
                        java.time.Clock.systemUTC(), new java.security.SecureRandom());
        UUID intent = IDS.next();
        UUID attempt = ids.next();
        UUID landed = IDS.next();
        UUID inFlight = IDS.next();
        com.finapp.payments.JdbcRefundStore refunds = new com.finapp.payments.JdbcRefundStore();
        com.finapp.payments.PaymentAttemptId attemptId =
                com.finapp.payments.PaymentAttemptId.of(attempt);
        com.finapp.sharedkernel.money.CurrencyCode eur =
                com.finapp.sharedkernel.money.CurrencyCode.of("EUR");

        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED");
            insertRefund(app, landed, attempt, 400, "EUR");
            insertRefund(app, inFlight, attempt, 300, "EUR");
            try (PreparedStatement complete =
                    app.prepareStatement(
                            "UPDATE payments.refund SET status = 'COMPLETED',"
                                    + " provider_reference = 'psp-refund-1' WHERE id = ?")) {
                complete.setObject(1, landed);
                assertThat(complete.executeUpdate()).isEqualTo(1);
            }

            assertThat(refunds.sumNonFailedFor(app, attemptId, eur).minorUnits())
                    .as("the BUDGET bound: both refunds have reserved their share")
                    .isEqualTo(700L);
            assertThat(refunds.sumCompletedFor(app, attemptId, eur).minorUnits())
                    .as("what has actually LEFT: only the one that completed. The in-flight"
                            + " refund can still fail, and a fee returned against it would be"
                            + " returned against money that never moved, with no producer for"
                            + " taking it back")
                    .isEqualTo(400L);
        }
    }

    @Test
    @DisplayName("provider evidence is append-only for every writer, and its shape CHECKs hold")
    void evidenceIsAppendOnlyForEveryWriter() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID evidence = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "AUTH_DISPATCHED");
            insertEvidence(app, evidence, attempt, null, "RESPONSE", 10);

            // The unattributable webhook is STILL retained - both subjects NULL is legal.
            assertThatCode(() ->
                            insertEvidence(app, IDS.next(), null, null, "WEBHOOK", 10))
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
                    app, IDS.next(), attempt, attempt, "REQUEST", 10));
            // An unknown kind, a plaintext passed off as ciphertext, a short nonce.
            assertSqlState(CHECK_VIOLATION, () -> insertEvidence(
                    app, IDS.next(), attempt, null, "SCREENSHOT", 10));
            assertSqlState(CHECK_VIOLATION, () -> insertRawEvidence(
                    app, IDS.next(), "RESPONSE", new byte[10], new byte[12], 10));
            assertSqlState(CHECK_VIOLATION, () -> insertRawEvidence(
                    app, IDS.next(), "RESPONSE", new byte[26], new byte[11], 10));
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
        UUID intent = IDS.next();
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
                event.setString(2, IDS.next().toString());
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
                        insertAttemptRow(connection, IDS.next(), intent, status, attempt))
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
                attempt.authProviderReference = "psp-auth-" + IDS.next();
                attempt.authorizedMinor = 1000L;
            }
            case "CAPTURE_DISPATCHED", "CAPTURE_UNKNOWN" -> {
                attempt.authProviderReference = "psp-auth-" + IDS.next();
                attempt.authorizedMinor = 1000L;
                attempt.captureReference = "cap-" + IDS.next();
            }
            case "CAPTURED" -> {
                attempt.authProviderReference = "psp-auth-" + IDS.next();
                attempt.authorizedMinor = 1000L;
                attempt.captureReference = "cap-" + IDS.next();
                attempt.captureProviderReference = "psp-cap-" + IDS.next();
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
                        + " payment_method_id, credit_account_id, amount_minor, currency,"
                        + " scale, status, created_at, capture_mode)"
                        + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, ?, ?, 'AUTOMATIC')")) {
            insert.setObject(1, id);
            insert.setObject(2, IDS.next());
            insert.setObject(3, IDS.next());
            insert.setObject(4, IDS.next());
            insert.setObject(5, IDS.next());
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
                        + " created_at, rail, interaction_model)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
            insert.setString(16, "card");
            insert.setString(17, "TWO_STEP");
            insert.executeUpdate();
        }
    }

    /**
     * A push or book row: no dispatch reference, no two-step fact, the mapped reason iff
     * FAILED - the foreign models' one coherent shape until their rails land (P7-TSK-002).
     */
    private static void insertForeignModelRow(
            Connection connection, UUID id, UUID intent, InteractionModel model, String status)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.payment_attempt (id, intent_id, status, failure_reason,"
                        + " created_at, rail, interaction_model)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, intent);
            insert.setString(3, status);
            insert.setString(4, "FAILED".equals(status) ? "DECLINED" : null);
            insert.setTimestamp(5, Timestamp.from(Instant.now()));
            insert.setString(6, model == InteractionModel.PUSH ? "push-test" : "book-test");
            insert.setString(7, model.name());
            insert.executeUpdate();
        }
    }

    private static void insertRefund(
            Connection connection, UUID id, UUID attempt, long amountMinor, String currency)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.refund (id, attempt_id, amount_minor, currency, scale,"
                        + " reason, hold_reference, provider_idempotency_reference,"
                        + " provider_reference, status, created_at, last_dispatched_at)"
                        + " VALUES (?, ?, ?, ?, 2, 'operator-recorded reason', ?, ?, NULL,"
                        + " 'DISPATCHED', ?, ?)")) {
            Timestamp born = Timestamp.from(Instant.now());
            insert.setObject(1, id);
            insert.setObject(2, attempt);
            insert.setLong(3, amountMinor);
            insert.setString(4, currency);
            insert.setObject(5, IDS.next());
            insert.setString(6, "refund-" + id);
            insert.setTimestamp(7, born);
            // The birth permit is the dispatch itself (V009): the same value as created_at.
            insert.setTimestamp(8, born);
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
