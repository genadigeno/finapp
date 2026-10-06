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
        // For EACH model, every (from, to) pair of the FULL fourteen-state vocabulary runs
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
            case VOID_DISPATCHED -> ", void_reference = 'void-swp-" + IDS.next() + "'";
            case VOIDED -> ", void_provider_reference = 'psp-void-swp-" + IDS.next() + "'";
            case FAILED -> ", failure_reason = 'DECLINED'";
            // The scheme's reference arrives exactly with the push completion
            // (P7-TSK-009's stage CHECK); only PUSH edges reach EXECUTED by a move.
            case EXECUTED -> ", scheme_reference = 'sch-swp-" + IDS.next() + "'";
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
                                + " status, created_at, rail, interaction_model,"
                                // The push birth facts ride along (P7-TSK-009), so the
                                // smuggled two-step reference stays the ONE violation.
                                + " end_to_end_reference, last_dispatched_at)"
                                + " VALUES (?, ?, ?, 'AWAITING_PAYER', now(), 'push-test',"
                                + " 'PUSH', ?, now())")) {
                    smuggled.setObject(1, IDS.next());
                    smuggled.setObject(2, intent);
                    smuggled.setString(3, "auth-smuggled-" + IDS.next());
                    smuggled.setString(4, IDS.next().toString().replace("-", ""));
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
                                + " interaction_model,"
                                // The push birth facts ride along (P7-TSK-009).
                                + " end_to_end_reference, last_dispatched_at)"
                                + " VALUES (?, ?, 'AWAITING_PAYER', ?, 1000, 'EUR', 2, now(),"
                                + " 'push-test', 'PUSH', ?, now())")) {
                    promise.setObject(1, IDS.next());
                    promise.setObject(2, intent);
                    promise.setString(3, "psp-auth-" + IDS.next());
                    promise.setString(4, IDS.next().toString().replace("-", ""));
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
            // hand-list that missed it would hold the intent's slot forever. The scheme's
            // reference rides the edge since P7-TSK-009: V017's push stage CHECK requires
            // it exactly at EXECUTED, for this raw writer too.
            try (PreparedStatement execute = app.prepareStatement(
                    "UPDATE payments.payment_attempt SET status = 'EXECUTED',"
                            + " scheme_reference = ? WHERE id = ?")) {
                execute.setString(1, "sch-slot-" + IDS.next());
                execute.setObject(2, running);
                assertThat(execute.executeUpdate()).isEqualTo(1);
            }
            assertThatCode(() -> insertForeignModelRow(app, IDS.next(), intent,
                            InteractionModel.PUSH, "AWAITING_PAYER"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("VOIDED frees the one-live-per-intent slot exactly as the other terminals do"
            + " (V014's regenerated predicate, P7-TSK-004)")
    void voidedFreesTheOneLiveSlot() throws Exception {
        UUID intent = IDS.next();
        UUID running = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, running, intent, "VOID_DISPATCHED");
            // The slot is held while the release is in flight...
            assertSqlState(UNIQUE_VIOLATION, () -> insertAttempt(
                    app, IDS.next(), intent, "AUTH_DISPATCHED"));
            // ...and VOIDED frees it: the fourth terminal is IN the generated predicate. A
            // hand-list that missed it would hold the intent's slot forever.
            try (PreparedStatement release = app.prepareStatement(
                    "UPDATE payments.payment_attempt SET status = 'VOIDED',"
                            + " void_provider_reference = ? WHERE id = ?")) {
                release.setString(1, "psp-void-slot-" + IDS.next());
                release.setObject(2, running);
                assertThat(release.executeUpdate()).isEqualTo(1);
            }
            assertThatCode(() -> insertAttempt(app, IDS.next(), intent, "AUTH_DISPATCHED"))
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

            // The void's shapes (P7-TSK-004, V014): a void state without OUR reference, and
            // the FAILED void without the promise it tried to release.
            assertCheck(migrator, intent, "VOID_DISPATCHED", attempt -> {
                attempt.voidReference = null;
            }, "payment_attempt_stage_facts_match_status");
            assertCheck(migrator, intent, "FAILED", attempt -> {
                attempt.voidReference = "void-orphan-" + IDS.next();
            }, "payment_attempt_stage_facts_match_status");
            // A void reference on a capture-stage row - the redirect is a STATE, not a flag.
            assertCheck(migrator, intent, "CAPTURE_DISPATCHED", attempt -> {
                attempt.voidReference = "void-early-" + IDS.next();
            }, "payment_attempt_stage_facts_match_status");
            // The acknowledgement exactly when VOIDED, both directions.
            assertCheck(migrator, intent, "VOIDED", attempt -> {
                attempt.voidProviderReference = null;
            }, "payment_attempt_void_ack_arrives_exactly_when_voided");
            assertCheck(migrator, intent, "VOID_UNKNOWN", attempt -> {
                attempt.voidProviderReference = "psp-void-early-" + IDS.next();
            }, "payment_attempt_void_ack_arrives_exactly_when_voided");

            // No void fact rides another model's row (V014's re-ADDed wall).
            assertThatThrownBy(() -> {
                try (PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO payments.payment_attempt (id, intent_id, status,"
                                + " created_at, rail, interaction_model, void_reference,"
                                // The push birth facts ride along (P7-TSK-009).
                                + " end_to_end_reference, last_dispatched_at)"
                                + " VALUES (?, ?, 'AWAITING_PAYER', now(), 'push-test',"
                                + " 'PUSH', ?, ?, now())")) {
                    insert.setObject(1, IDS.next());
                    insert.setObject(2, intent);
                    insert.setString(3, "void-foreign-" + IDS.next());
                    insert.setString(4, IDS.next().toString().replace("-", ""));
                    insert.executeUpdate();
                }
            })
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> {
                        assertThat(((SQLException) failure).getSQLState())
                                .isEqualTo(CHECK_VIOLATION);
                        assertThat(failure.getMessage())
                                .contains(
                                        "payment_attempt_foreign_model_carries_no_two_step"
                                                + "_facts");
                    });
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
    @DisplayName("the return bound is per-model (V018, P7-TSK-010): a push refund is judged"
            + " against the EXECUTED intent's amount, a waiting pay-in refused, and a BOOK"
            + " row refused outright - for every writer")
    void theReturnBoundIsPerModelForEveryWriter() throws Exception {
        UUID executedIntent = IDS.next();
        UUID executed = IDS.next();
        UUID waitingIntent = IDS.next();
        UUID waiting = IDS.next();
        UUID bookIntent = IDS.next();
        UUID book = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, executedIntent, "SUCCEEDED");
            insertForeignModelRow(app, executed, executedIntent, InteractionModel.PUSH,
                    "EXECUTED");
            insertIntent(app, waitingIntent, "PROCESSING");
            insertForeignModelRow(app, waiting, waitingIntent, InteractionModel.PUSH,
                    "AWAITING_PAYER");
            insertIntent(app, bookIntent, "SUCCEEDED");
            insertForeignModelRow(app, book, bookIntent, InteractionModel.BOOK, "EXECUTED");

            // The push base is the intent's frozen ask (1000): to the penny legal...
            insertRefund(app, IDS.next(), executed, 999, "EUR");
            insertRefund(app, IDS.next(), executed, 1, "EUR");
            // ...one minor unit past it creates money (INV-PAY-05's push half).
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), executed, 1, "EUR"));

            // Only an EXECUTED pay-in has anything to return.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), waiting, 1, "EUR"));

            // The execution's currency, or nothing (INV-MON-03).
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), executed, 1, "USD"));

            // The BOOK arm (V019, P7-TSK-011): the same executed judgement - to the penny
            // legal, one past the intent's ask refused, and only EXECUTED has anything to
            // return. (Until V019 this row was refused outright: the producer had not
            // shipped, and V018 holds that refusal as applied history.)
            insertRefund(app, IDS.next(), book, 1000, "EUR");
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), book, 1, "EUR"));
            UUID failedBookIntent = IDS.next();
            UUID failedBook = IDS.next();
            insertIntent(app, failedBookIntent, "FAILED");
            insertForeignModelRow(app, failedBook, failedBookIntent, InteractionModel.BOOK,
                    "FAILED");
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(app, IDS.next(), failedBook, 1, "EUR"));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // For every writer - the migrator meets the same push arm.
            assertSqlState(CHECK_VIOLATION, () ->
                    insertRefund(migrator, IDS.next(), executed, 1, "EUR"));
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

    /**
     * X-TSK-013 at the schema: each of the five Phase 5-7 permits carries the trigger that re-stamps a
     * forward write with the database's instant, AFTER its machine trigger (BEFORE triggers fire in
     * name order), so a backward write is still refused and a writer's future instant never lands.
     */
    @Test
    @DisplayName("every Phase 5-7 send permit is the database's: a writer's future instant is overwritten"
            + " with statement_timestamp(), strictly forward, and a backward write is still refused"
            + " (X-TSK-013)")
    void everySendPermitIsTheDatabases() throws Exception {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT n.nspname || '.' || c.relname || ':' || t.tgname"
                                        + " FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                        + " WHERE t.tgname LIKE '%send_permit_is_the_databases'"
                                        + " AND t.tgenabled = 'O' ORDER BY 1");
                ResultSet rows = read.executeQuery()) {
            List<String> triggers = new java.util.ArrayList<>();
            while (rows.next()) {
                triggers.add(rows.getString(1));
            }
            assertThat(triggers)
                    .containsExactly(
                            "merchant.merchant_payout:merchant_payout_send_permit_is_the_databases",
                            "payments.dispute_response:dispute_response_send_permit_is_the_databases",
                            "payments.payment_attempt:payment_attempt_send_permit_is_the_databases",
                            "payments.refund:refund_send_permit_is_the_databases",
                            "payments.withdrawal:withdrawal_send_permit_is_the_databases");
            assertThat(triggers)
                    .as("each fires after its table's machine trigger, which keeps refusing a backward write")
                    .allSatisfy(trigger -> assertThat(trigger.substring(trigger.indexOf(':') + 1))
                            .isGreaterThan(trigger.substring(trigger.indexOf('.') + 1, trigger.indexOf(':'))
                                    + "_permits_only_machine_edges"));
        }

        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID refund = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "PROCESSING");
            insertAttempt(app, attempt, intent, "CAPTURED");
            insertRefund(app, refund, attempt, 500, "EUR");
            // An instance ninety seconds AHEAD writes its own instant: the database's lands instead.
            assertThat(updated(app,
                            "UPDATE payments.refund SET last_dispatched_at = now() + interval '90 seconds'"
                                    + " WHERE id = ?", refund))
                    .isEqualTo(1);
            try (PreparedStatement stamped =
                    app.prepareStatement(
                            // Well short of the writer's +90 s - the raw seed's birth is the host's instant,
                            // up to a couple of seconds off the database's, and GREATEST keeps it if later.
                            "SELECT last_dispatched_at < statement_timestamp() + interval '30 seconds',"
                                    + " last_dispatched_at > created_at"
                                    + " FROM payments.refund WHERE id = ?")) {
                stamped.setObject(1, refund);
                try (ResultSet row = stamped.executeQuery()) {
                    row.next();
                    assertThat(row.getBoolean(1)).as("the writer's future instant never landed").isTrue();
                    assertThat(row.getBoolean(2)).as("and the permit still moved forward").isTrue();
                }
            }
            assertThatThrownBy(() -> updated(app,
                            "UPDATE payments.refund SET last_dispatched_at = last_dispatched_at - interval"
                                    + " '30 seconds' WHERE id = ?", refund))
                    .hasMessageContaining("send permit only moves forward");
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
        String voidReference;
        String voidProviderReference;
        String reason;
    }

    @FunctionalInterface
    private interface Corruption {
        void applyTo(AttemptRow attempt);
    }

    @Test
    @DisplayName("V020 binds EVERY writer (P7-TSK-012): a dispute is born only at an entry"
            + " stage, moves only along the machine's edges, never leaves a terminal stage,"
            + " its opening statement never moves - inside a legal edge included - its"
            + " reference names one dispute, and the trail is append-only")
    void theDisputeSchemaBindsEveryWriter() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID dispute = IDS.next();
        String reference = someDisputeReference();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "SUCCEEDED");
            insertAttempt(app, attempt, intent, "CAPTURED");
            // Born at an entry stage and nowhere else (INV-LIFE-02 at birth).
            for (String stage : List.of("REPRESENTED", "WON", "LOST", "ACCEPTED", "CLOSED")) {
                assertSqlState(CHECK_VIOLATION,
                        () -> insertDispute(app, IDS.next(), attempt, someDisputeReference(),
                                stage));
            }
            insertDispute(app, dispute, attempt, reference, "CHARGED_BACK");
            // The network's reference names ONE dispute - the opening's arbiter.
            assertSqlState(UNIQUE_VIOLATION,
                    () -> insertDispute(app, IDS.next(), attempt, reference, "INQUIRY"));
            // A sibling edge and a backward one are refused (P0001).
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET stage = 'CLOSED' WHERE id = ?", dispute));
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET stage = 'INQUIRY' WHERE id = ?", dispute));
            // A legal edge moves - and then the terminal refuses every stage (INV-LIFE-04).
            assertThat(updated(app,
                            "UPDATE payments.dispute SET stage = 'LOST' WHERE id = ?", dispute))
                    .isEqualTo(1);
            for (String stage :
                    List.of("INQUIRY", "CHARGED_BACK", "REPRESENTED", "WON", "ACCEPTED",
                            "CLOSED")) {
                assertSqlState(RAISED, () -> updated(app,
                        "UPDATE payments.dispute SET stage = '" + stage + "' WHERE id = ?",
                        dispute));
            }
            // The application role cannot name a frozen column at all: its grant is the stage
            // and the arriving chargeback.
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "UPDATE payments.dispute SET reason = 'AUTHORIZATION' WHERE id = ?",
                    dispute));
            // The chargeback's amount it CAN name moves only NULL -> value: a recorded one is
            // never revised or removed, for this role as for every other (P0001).
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET chargeback_amount_minor ="
                            + " chargeback_amount_minor + 1 WHERE id = ?",
                    dispute));
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "DELETE FROM payments.dispute WHERE id = ?", dispute));
            // The trail is append-only by privilege.
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "UPDATE payments.dispute_event SET to_stage = to_stage WHERE dispute_id = ?",
                    dispute));
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "DELETE FROM payments.dispute_event WHERE dispute_id = ?", dispute));
        }

        UUID inquiry = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertDispute(app, inquiry, attempt, someDisputeReference(), "INQUIRY");
            // The chargeback's amount exists exactly when the funds are taken: an inquiry
            // born with one, a chargeback born without one, and an escalation that brings
            // none are all refused by the coherence CHECK (23514).
            assertSqlState(CHECK_VIOLATION,
                    () -> insertDispute(app, IDS.next(), attempt, someDisputeReference(),
                            "INQUIRY", 1000L));
            assertSqlState(CHECK_VIOLATION,
                    () -> insertDispute(app, IDS.next(), attempt, someDisputeReference(),
                            "CHARGED_BACK", null));
            assertSqlState(CHECK_VIOLATION, () -> updated(app,
                    "UPDATE payments.dispute SET stage = 'CHARGED_BACK' WHERE id = ?", inquiry));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The freeze binds the table's owner: a stage-preserving edit...
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute SET reason = 'AUTHORIZATION' WHERE id = ?",
                    inquiry));
            // ...and one smuggled inside a LEGAL edge, where only the freeze clause can refuse
            // (the P7-TSK-011 lesson: the edge clause and the coherence CHECK would both pass
            // an escalation that brings its chargeback).
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute SET stage = 'CHARGED_BACK',"
                            + " chargeback_amount_minor = 800, chargeback_currency = 'EUR',"
                            + " chargeback_scale = 2, reason = 'AUTHORIZATION' WHERE id = ?",
                    inquiry));
            // An illegal edge and an illegal birth, for the migrator too.
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute SET stage = 'WON' WHERE id = ?", inquiry));
            assertSqlState(CHECK_VIOLATION,
                    () -> insertDispute(migrator, IDS.next(), attempt, someDisputeReference(),
                            "WON"));
        }
    }

    @Test
    @DisplayName("V022 binds EVERY writer (P7-TSK-014): the respond-by deadline rides the"
            + " chargeback and moves only NULL -> value; a dispute response is born DISPATCHED,"
            + " moves only along its machine, keeps ONE live answer per dispute, its permit"
            + " forward and its dispatch frozen - for the migrator too; evidence is append-only"
            + " for the application role; provider evidence names at most one of FOUR subjects")
    void theRepresentmentSchemaBindsEveryWriter() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID inquiry = IDS.next();
        UUID charged = IDS.next();
        UUID first = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "SUCCEEDED");
            insertAttempt(app, attempt, intent, "CAPTURED");
            insertDispute(app, inquiry, attempt, someDisputeReference(), "INQUIRY");
            insertDispute(app, charged, attempt, someDisputeReference(), "CHARGED_BACK");

            // The deadline rides the chargeback, and moves only NULL -> value.
            assertCheckNamed(
                    () -> updated(app,
                            "UPDATE payments.dispute SET respond_by = now() WHERE id = ?", inquiry),
                    "dispute_respond_by_rides_the_chargeback");
            assertThat(updated(app,
                            "UPDATE payments.dispute SET respond_by = now() + interval '7 days'"
                                    + " WHERE id = ?", charged))
                    .isEqualTo(1);
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET respond_by = now() + interval '9 days'"
                            + " WHERE id = ?", charged));
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET respond_by = NULL WHERE id = ?", charged));

            // Born DISPATCHED; the evidence matches the kind; ONE live answer per dispute.
            assertSqlState(CHECK_VIOLATION,
                    () -> insertResponse(app, IDS.next(), charged, "SUBMITTED", "ACCEPTANCE"));
            assertCheckNamed(
                    () -> insertResponse(app, IDS.next(), charged, "DISPATCHED", "REPRESENTMENT"),
                    "dispute_response_evidence_matches_kind");
            insertResponse(app, first, charged, "DISPATCHED", "ACCEPTANCE");
            assertSqlState(UNIQUE_VIOLATION,
                    () -> insertResponse(app, IDS.next(), charged, "DISPATCHED", "ACCEPTANCE"));

            // The machine's edges, the outcome's companions, the permit forward.
            assertThat(updated(app,
                            "UPDATE payments.dispute_response SET status = 'UNKNOWN' WHERE id = ?",
                            first))
                    .isEqualTo(1);
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute_response SET status = 'DISPATCHED' WHERE id = ?",
                    first));
            assertCheckNamed(
                    () -> updated(app,
                            "UPDATE payments.dispute_response SET status = 'SUBMITTED'"
                                    + " WHERE id = ?", first),
                    "dispute_response_provider_reference_matches_status");
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute_response SET send_permit = send_permit"
                            + " - interval '1 minute' WHERE id = ?", first));
            assertThat(updated(app,
                            "UPDATE payments.dispute_response SET send_permit = send_permit"
                                    + " + interval '1 minute' WHERE id = ?", first))
                    .isEqualTo(1);
            assertThat(updated(app,
                            "UPDATE payments.dispute_response SET status = 'SUBMITTED',"
                                    + " provider_reference = 'psp_dr_schema' WHERE id = ?", first))
                    .isEqualTo(1);
            // Terminal is terminal: no outcome moves, no send follows.
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute_response SET status = 'FAILED',"
                            + " failure_reason = 'DECLINED', provider_reference = NULL"
                            + " WHERE id = ?", first));
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute_response SET send_permit = send_permit"
                            + " + interval '1 minute' WHERE id = ?", first));
            // The application role may not touch the dispatch at all.
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "UPDATE payments.dispute_response SET dispatch_key = 'other' WHERE id = ?",
                    first));

            // Evidence is append-only for the application role.
            UUID document = IDS.next();
            insertEvidence(app, document, charged);
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "UPDATE payments.dispute_evidence SET kind = 'OTHER' WHERE id = ?", document));
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> updated(app,
                    "DELETE FROM payments.dispute_evidence WHERE id = ?", document));

            // Provider evidence names at most one subject - now of four.
            assertCheckNamed(
                    () -> {
                        try (PreparedStatement insert = app.prepareStatement(
                                "INSERT INTO payments.provider_evidence (id, attempt_id,"
                                        + " dispute_response_id, kind, content_ciphertext,"
                                        + " content_nonce, key_version, checksum_sha256,"
                                        + " content_length, recorded_at)"
                                        + " VALUES (?, ?, ?, 'RESPONSE', ?, ?, 1, ?, 1, now())")) {
                            insert.setObject(1, IDS.next());
                            insert.setObject(2, attempt);
                            insert.setObject(3, first);
                            insert.setBytes(4, new byte[17]);
                            insert.setBytes(5, new byte[12]);
                            insert.setBytes(6, new byte[32]);
                            insert.executeUpdate();
                        }
                    },
                    "provider_evidence_has_at_most_one_subject");
        }

        // The table's owner is bound too: the dispatch frozen, the edges the machine's.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute_response SET dispatch_key = 'other' WHERE id = ?",
                    first));
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute_response SET evidence_ids ="
                            + " ARRAY[gen_random_uuid()] WHERE id = ?", first));
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute SET respond_by = now() WHERE id = ?", charged));
        }
    }

    /** A dispute response row, born as {@code status} says - the birth trigger judges it. */
    private static int insertResponse(
            Connection connection, UUID id, UUID dispute, String status, String kind)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.dispute_response (id, dispute_id, kind, status,"
                        + " failure_reason, provider_idempotency_reference, provider_reference,"
                        + " evidence_ids, requested_by_id, requested_by_type, reason,"
                        + " dispatch_scope, dispatch_key, send_permit, created_at)"
                        + " VALUES (?, ?, ?, ?, NULL, ?, ?, '{}', 'schema-test', 'MERCHANT', NULL,"
                        + " 'dispute.respond:merchant:schema', ?, now(), now())")) {
            insert.setObject(1, id);
            insert.setObject(2, dispute);
            insert.setString(3, kind);
            insert.setString(4, status);
            insert.setString(5, "dsr-" + id);
            insert.setString(6, status.equals("SUBMITTED") ? "psp_dr_" + id : null);
            insert.setString(7, UUID.randomUUID().toString());
            return insert.executeUpdate();
        }
    }

    /** A syntactically valid evidence row: GCM's tag arithmetic and the key facts satisfied. */
    private static void insertEvidence(Connection connection, UUID id, UUID dispute)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.dispute_evidence (id, dispute_id, kind, content_type,"
                        + " content_ciphertext, content_nonce, key_version, checksum_sha256,"
                        + " content_length, uploaded_by_id, uploaded_by_type, uploaded_at)"
                        + " VALUES (?, ?, 'RECEIPT', 'PDF', ?, ?, 1, ?, 1, 'schema-test',"
                        + " 'MERCHANT', now())")) {
            insert.setObject(1, id);
            insert.setObject(2, dispute);
            insert.setBytes(3, new byte[17]);
            insert.setBytes(4, new byte[12]);
            byte[] checksum = new byte[32];
            new java.security.SecureRandom().nextBytes(checksum);
            insert.setBytes(5, checksum);
            insert.executeUpdate();
        }
    }

    /** A coherent dispute row: the chargeback's amount present exactly when charged back. */
    private static void insertDispute(
            Connection connection, UUID id, UUID attempt, String reference, String stage)
            throws SQLException {
        insertDispute(connection, id, attempt, reference, stage,
                stage.equals("INQUIRY") || stage.equals("CLOSED") ? null : 1000L);
    }

    /**
     * With the chargeback, its attribution arrives (V021, P7-TSK-013): an attribution of
     * nothing - the whole chargeback excess - so a raw seed never depends on the bound's
     * headroom.
     */
    private static void insertDispute(
            Connection connection,
            UUID id,
            UUID attempt,
            String reference,
            String stage,
            Long chargebackMinor)
            throws SQLException {
        insertDispute(connection, id, attempt, reference, stage, chargebackMinor,
                chargebackMinor == null ? null : 0L, chargebackMinor == null ? null : 0L);
    }

    private static void insertDispute(
            Connection connection,
            UUID id,
            UUID attempt,
            String reference,
            String stage,
            Long chargebackMinor,
            Long counterpartyShareMinor,
            Long parkedShareMinor)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.dispute (id, provider, provider_dispute_reference,"
                        + " attempt_id, reason, stage, chargeback_amount_minor,"
                        + " chargeback_currency, chargeback_scale,"
                        + " counterparty_share_amount_minor, counterparty_share_currency,"
                        + " counterparty_share_scale, parked_share_amount_minor,"
                        + " parked_share_currency, parked_share_scale, opened_at)"
                        + " VALUES (?, 'simulated-card', ?, ?, 'FRAUD', ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, reference);
            insert.setObject(3, attempt);
            insert.setString(4, stage);
            insert.setObject(5, chargebackMinor);
            insert.setString(6, chargebackMinor == null ? null : "EUR");
            insert.setObject(7, chargebackMinor == null ? null : (short) 2);
            insert.setObject(8, counterpartyShareMinor);
            insert.setString(9, counterpartyShareMinor == null ? null : "EUR");
            insert.setObject(10, counterpartyShareMinor == null ? null : (short) 2);
            insert.setObject(11, parkedShareMinor);
            insert.setString(12, parkedShareMinor == null ? null : "EUR");
            insert.setObject(13, parkedShareMinor == null ? null : (short) 2);
            insert.setTimestamp(14, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    @Test
    @DisplayName("V021 binds EVERY writer (P7-TSK-013): the combined bound BOTH ways under the"
            + " refund bound's namespace - a refund past what standing chargebacks left, an"
            + " attribution past what refunds and siblings left, anything attributed where"
            + " nothing was captured - the attribution arriving with the chargeback and only"
            + " ever growing by same-stage re-attribution while it stands, and the fee recorded"
            + " once, only with a chargeback, for the migrator too")
    void theChargebackAccountingSchemaBindsEveryWriter() throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        UUID first = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, intent, "SUCCEEDED");
            insertAttempt(app, attempt, intent, "CAPTURED"); // captured 10.00
            // A standing chargeback charging the counterparty 3.00 of its 10.00.
            insertDispute(app, first, attempt, someDisputeReference(), "CHARGED_BACK",
                    1000L, 300L, 0L);
            // The refund half: 7.01 more would take the counterparty past its credit.
            assertThatThrownBy(() -> insertRefund(app, IDS.next(), attempt, 701, "EUR"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("refunds and standing chargebacks together")
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
            insertRefund(app, IDS.next(), attempt, 200, "EUR"); // 5.00 of headroom remains
            // The dispute half: a second cycle may not attribute past what is left - posted
            // or parked alike, a parked share is the counterparty's by attribution.
            for (long[] shares : new long[][] {{501L, 0L}, {0L, 501L}, {300L, 201L}}) {
                assertThatThrownBy(() -> insertDispute(app, IDS.next(), attempt,
                                someDisputeReference(), "CHARGED_BACK", 800L, shares[0],
                                shares[1]))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("payments_dispute_attribution_is_bounded");
            }
            UUID second = IDS.next();
            insertDispute(app, second, attempt, someDisputeReference(), "CHARGED_BACK",
                    800L, 200L, 0L); // 3.00 of headroom remains
            // The attribution never shrinks, never moves across an edge...
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET counterparty_share_amount_minor = 299"
                            + " WHERE id = ?", first));
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET stage = 'REPRESENTED',"
                            + " counterparty_share_amount_minor = 350 WHERE id = ?", first));
            // ...grows by a same-stage re-attribution while it stands, within the bound...
            assertThat(updated(app,
                            "UPDATE payments.dispute SET counterparty_share_amount_minor = 350"
                                    + " WHERE id = ?", first))
                    .isEqualTo(1);
            assertThatThrownBy(() -> updated(app,
                            "UPDATE payments.dispute SET counterparty_share_amount_minor = 651"
                                    + " WHERE id = ?", first))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("payments_dispute_attribution_is_bounded");
            // ...and a won chargeback's attribution was reversed with the funds: frozen.
            updated(app, "UPDATE payments.dispute SET stage = 'REPRESENTED' WHERE id = ?", first);
            updated(app, "UPDATE payments.dispute SET stage = 'WON' WHERE id = ?", first);
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET counterparty_share_amount_minor = 400"
                            + " WHERE id = ?", first));
            // The fee: only with a chargeback, recorded once, in its currency.
            assertThat(updated(app,
                            "UPDATE payments.dispute SET dispute_fee_amount_minor = 1500,"
                                    + " dispute_fee_currency = 'EUR', dispute_fee_scale = 2"
                                    + " WHERE id = ?", second))
                    .isEqualTo(1);
            assertSqlState(RAISED, () -> updated(app,
                    "UPDATE payments.dispute SET dispute_fee_amount_minor = 1600 WHERE id = ?",
                    second));
        }

        // Coherence on a fresh captured attempt, where no bound interferes: each CHECK named.
        UUID fresh = IDS.next();
        UUID freshIntent = IDS.next();
        UUID inquiry = IDS.next();
        UUID charged = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, freshIntent, "SUCCEEDED");
            insertAttempt(app, fresh, freshIntent, "CAPTURED");
            assertCheckNamed(
                    () -> insertDispute(app, IDS.next(), fresh, someDisputeReference(),
                            "INQUIRY", null, 0L, 0L),
                    "dispute_attribution_matches_chargeback");
            assertCheckNamed(
                    () -> insertDispute(app, IDS.next(), fresh, someDisputeReference(),
                            "CHARGED_BACK", 1000L, null, null),
                    "dispute_attribution_matches_chargeback");
            assertCheckNamed(
                    () -> insertDispute(app, IDS.next(), fresh, someDisputeReference(),
                            "CHARGED_BACK", 100L, 60L, 50L),
                    "dispute_attribution_within_chargeback");
            insertDispute(app, inquiry, fresh, someDisputeReference(), "INQUIRY");
            insertDispute(app, charged, fresh, someDisputeReference(), "CHARGED_BACK", 1000L,
                    0L, 0L);
            assertCheckNamed(
                    () -> updated(app,
                            "UPDATE payments.dispute SET counterparty_share_currency = 'GBP'"
                                    + " WHERE id = ?", charged),
                    "dispute_attribution_in_chargeback_currency");
            assertCheckNamed(
                    () -> updated(app,
                            "UPDATE payments.dispute SET dispute_fee_amount_minor = 1500,"
                                    + " dispute_fee_currency = 'EUR', dispute_fee_scale = 2"
                                    + " WHERE id = ?", inquiry),
                    "dispute_fee_rides_the_chargeback");
            assertCheckNamed(
                    () -> updated(app,
                            "UPDATE payments.dispute SET dispute_fee_amount_minor = 1500,"
                                    + " dispute_fee_currency = 'GBP', dispute_fee_scale = 2"
                                    + " WHERE id = ?", charged),
                    "dispute_fee_rides_the_chargeback");
        }

        // Nothing captured credits nobody: not a unit may be attributed.
        UUID authorized = IDS.next();
        UUID authorizedIntent = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertIntent(app, authorizedIntent, "PROCESSING");
            insertAttempt(app, authorized, authorizedIntent, "AUTHORIZED");
            assertThatThrownBy(() -> insertDispute(app, IDS.next(), authorized,
                            someDisputeReference(), "CHARGED_BACK", 1000L, 1L, 0L))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("payments_dispute_attribution_is_bounded");
            insertDispute(app, IDS.next(), authorized, someDisputeReference(), "CHARGED_BACK",
                    1000L, 0L, 0L); // all excess: recorded, never refused
        }

        // The table's owner is bound too: the second cycle's 2.00 never shrinks, and never
        // grows past what the refunds left once the won chargeback stood no more.
        UUID secondCycle = secondCycleOn(attempt);
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertSqlState(RAISED, () -> updated(migrator,
                    "UPDATE payments.dispute SET counterparty_share_amount_minor = 100"
                            + " WHERE id = ?", secondCycle));
            assertThatThrownBy(() -> updated(migrator,
                            "UPDATE payments.dispute SET counterparty_share_amount_minor = 801"
                                    + " WHERE id = ?", secondCycle))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("payments_dispute_attribution_is_bounded");
        }
    }

    /** The second-cycle dispute the V021 test left standing on {@code attempt}. */
    private static UUID secondCycleOn(UUID attempt) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT id FROM payments.dispute WHERE attempt_id = ?"
                                + " AND stage = 'CHARGED_BACK' AND counterparty_share_amount_minor"
                                + " = 200")) {
            read.setObject(1, attempt);
            try (java.sql.ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    /** A 23514 whose message names {@code constraint} - the SQLSTATE alone cannot tell two
     * CHECKs apart. */
    private static void assertCheckNamed(SqlAction action, String constraint) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint)
                .extracting(failure -> ((SQLException) failure).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    private static String someDisputeReference() {
        return "dp_" + IDS.next().toString().replace("-", "");
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
            case "VOID_DISPATCHED", "VOID_UNKNOWN" -> {
                attempt.authProviderReference = "psp-auth-" + IDS.next();
                attempt.authorizedMinor = 1000L;
                attempt.voidReference = "void-" + IDS.next();
            }
            case "VOIDED" -> {
                attempt.authProviderReference = "psp-auth-" + IDS.next();
                attempt.authorizedMinor = 1000L;
                attempt.voidReference = "void-" + IDS.next();
                attempt.voidProviderReference = "psp-void-" + IDS.next();
            }
            case "FAILED" -> attempt.reason = "DECLINED";
            default -> throw new IllegalArgumentException(status);
        }
        return attempt;
    }

    @Test
    @DisplayName("the intent's instrument-choice XOR and its NULL-SAFE freeze hold for"
            + " every writer (V019, P7-TSK-011): both and neither refuse at INSERT, and"
            + " neither instrument column moves after birth - the V012 <> was NULL-blind,"
            + " which is exactly the edit the recreated trigger must refuse")
    void theInstrumentChoiceHoldsForEveryWriter() throws Exception {
        UUID walletIntent = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            insertWalletIntent(app, walletIntent, "REQUIRES_CONFIRMATION");

            // BOTH and NEITHER refuse at the CHECK, whoever writes.
            assertSqlState(CHECK_VIOLATION, () -> {
                try (PreparedStatement insert = app.prepareStatement(
                        "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                                + " payment_method_id, credit_account_id, amount_minor,"
                                + " currency, scale, status, created_at, capture_mode,"
                                + " debit_account_id)"
                                + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2,"
                                + " 'REQUIRES_CONFIRMATION', now(), 'AUTOMATIC', ?)")) {
                    insert.setObject(1, IDS.next());
                    insert.setObject(2, IDS.next());
                    insert.setObject(3, IDS.next());
                    insert.setObject(4, IDS.next());
                    insert.setObject(5, IDS.next());
                    insert.setObject(6, IDS.next());
                    insert.executeUpdate();
                }
            });
            assertSqlState(CHECK_VIOLATION, () -> {
                try (PreparedStatement insert = app.prepareStatement(
                        "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                                + " credit_account_id, amount_minor, currency, scale,"
                                + " status, created_at, capture_mode)"
                                + " VALUES (?, ?, ?, ?, 1000, 'EUR', 2,"
                                + " 'REQUIRES_CONFIRMATION', now(), 'AUTOMATIC')")) {
                    insert.setObject(1, IDS.next());
                    insert.setObject(2, IDS.next());
                    insert.setObject(3, IDS.next());
                    insert.setObject(4, IDS.next());
                    insert.executeUpdate();
                }
            });

            // The app role cannot even REACH the instrument columns: its UPDATE grant
            // is (status) alone, so the privilege wall refuses first - the freeze's
            // outer defence, asserted as such.
            assertSqlState("42501", () -> {
                try (PreparedStatement update = app.prepareStatement(
                        "UPDATE payments.payment_intent SET payment_method_id = ?,"
                                + " debit_account_id = NULL WHERE id = ?")) {
                    update.setObject(1, IDS.next());
                    update.setObject(2, walletIntent);
                    update.executeUpdate();
                }
            });
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            // The NULL-safe freeze, ISOLATED - and isolation needs a LEGAL EDGE: the
            // intent trigger refuses every non-edge update in its edge clause, so a
            // status-preserving edit raises P0001 whatever the freeze says (the first
            // version of this assertion did exactly that, and the P7-TSK-011 gate's
            // probe caught it surviving a NULL-blind revert). Riding REQUIRES_CONFIRMATION
            // -> PROCESSING while attaching a method leaves the freeze as the ONLY
            // clause that can refuse: NULL-safe raises P0001 in the BEFORE trigger;
            // V012's NULL-blind <> stays silent and the XOR CHECK answers 23514 instead.
            // The SQLSTATE is the discriminator (the P7-TSK-007 class, at the intent).
            assertSqlState(RAISED, () -> {
                try (PreparedStatement update = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET status = 'PROCESSING',"
                                + " payment_method_id = ? WHERE id = ?")) {
                    update.setObject(1, IDS.next());
                    update.setObject(2, walletIntent);
                    update.executeUpdate();
                }
            });
            // And the swap the XOR itself admits (method set, debit cleared) - the
            // frozen-facts rule, whichever column raises first.
            assertSqlState(RAISED, () -> {
                try (PreparedStatement update = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET payment_method_id = ?,"
                                + " debit_account_id = NULL WHERE id = ?")) {
                    update.setObject(1, IDS.next());
                    update.setObject(2, walletIntent);
                    update.executeUpdate();
                }
            });
            assertSqlState(RAISED, () -> {
                try (PreparedStatement update = migrator.prepareStatement(
                        "UPDATE payments.payment_intent SET debit_account_id = ?"
                                + " WHERE id = ?")) {
                    update.setObject(1, IDS.next());
                    update.setObject(2, walletIntent);
                    update.executeUpdate();
                }
            });
        }
    }

    /** A wallet-instrument intent (P7-TSK-011): debit side set, no method - V019's XOR. */
    private static void insertWalletIntent(Connection connection, UUID id, String status)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " credit_account_id, amount_minor, currency,"
                        + " scale, status, created_at, capture_mode, debit_account_id)"
                        + " VALUES (?, ?, ?, ?, 1000, 'EUR', 2, ?, ?, 'AUTOMATIC', ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, IDS.next());
            insert.setObject(3, IDS.next());
            insert.setObject(4, IDS.next());
            insert.setString(5, status);
            insert.setTimestamp(6, Timestamp.from(Instant.now()));
            insert.setObject(7, IDS.next());
            insert.executeUpdate();
        }
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
                        + " created_at, rail, interaction_model, void_reference,"
                        + " void_provider_reference)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?)")) {
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
            insert.setString(18, attempt.voidReference);
            insert.setString(19, attempt.voidProviderReference);
            insert.executeUpdate();
        }
    }

    /**
     * A push or book row: no dispatch reference, no two-step fact, the mapped reason iff
     * FAILED — and since `P7-TSK-009` a PUSH row carries its own birth facts (the
     * end-to-end reference and the initiation permit), because `V017` requires them of
     * every writer exactly as the aggregate does.
     */
    private static void insertForeignModelRow(
            Connection connection, UUID id, UUID intent, InteractionModel model, String status)
            throws SQLException {
        boolean push = model == InteractionModel.PUSH;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payments.payment_attempt (id, intent_id, status, failure_reason,"
                        + " created_at, rail, interaction_model, end_to_end_reference,"
                        + " last_dispatched_at, scheme_reference)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, intent);
            insert.setString(3, status);
            insert.setString(4, "FAILED".equals(status) ? "DECLINED" : null);
            Timestamp born = Timestamp.from(Instant.now());
            insert.setTimestamp(5, born);
            // A DECLARED rail of each model, never an invented one: these rows commit into the
            // tier's one shared database, and the opening backfill pages every EXECUTED and
            // CAPTURED attempt through PaymentRails - an undeclared rail is ADR-0059's wiring
            // fault, so one 'push-test' row answered 500 to every later backfill in the JVM.
            // The schema cannot tell the rails apart; the backfill skips these (no entry).
            insert.setString(6, push ? "instant" : "book");
            insert.setString(7, model.name());
            insert.setString(
                    8, push ? IDS.next().toString().replace("-", "") : null);
            insert.setTimestamp(9, push ? born : null);
            // The scheme's reference exactly when a push row is EXECUTED (P7-TSK-009's
            // stage CHECK), for this raw writer too - unique, the platform-wide arbiter.
            insert.setString(
                    10,
                    push && "EXECUTED".equals(status)
                            ? "sch-fmr-" + IDS.next()
                            : null);
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
