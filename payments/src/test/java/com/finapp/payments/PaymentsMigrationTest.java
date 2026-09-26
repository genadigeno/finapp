package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002`–`V005` and the code cannot drift (`P5-TSK-008`, the {@code P0-TSK-022} pattern) — the
 * consumer the three machine tasks pinned their SQL fragments against, arriving. Every
 * generated artefact has one definition: the status {@code CHECK}s (three per machine — the
 * table and the history's from/to columns) from each enum's {@code sqlValueList()}, the reason
 * {@code CHECK} from {@link PaymentFailureReason#sqlValueList()}, the monetary shapes from
 * {@link MoneyColumns.ColumnNames#ddl()} and — new with this schema —
 * {@link MoneyColumns.ColumnNames#nullableDdl()} (the attempt's amounts arrive with later
 * transitions), the reference shapes from {@link ProviderIdempotencyReference#MAX_LENGTH} and
 * {@link ProviderReference#MAX_LENGTH}, the refund reason bound from
 * {@link Refund#MAX_REASON_LENGTH}, the evidence size bound from
 * {@code PspWireClient.MAX_EVIDENCE_BYTES} (the retention bound `P5-TSK-003` stated, arriving
 * at exactly the reconciliation it named), the one-live-attempt predicate from
 * {@link PaymentAttemptStatus#sqlTerminalValueList()}, and every transition trigger's edge
 * conditions from {@code permittedTransitions()} — the sharp one, because an edge added to a
 * machine without its trigger half is a transition the aggregate permits and every other
 * writer is refused, and an edge the machine lost is a move raw SQL can make that the domain
 * cannot.
 */
@DisplayName("payments migration reconciliation (P5-TSK-008)")
class PaymentsMigrationTest {

    private static final String INTENT =
            "db/migration/payments/V002__create_payment_intent_and_history.sql";
    private static final String ATTEMPT =
            "db/migration/payments/V003__create_payment_attempt_and_history.sql";
    private static final String REFUND =
            "db/migration/payments/V004__create_refund_and_history.sql";
    private static final String EVIDENCE =
            "db/migration/payments/V005__create_provider_evidence.sql";
    private static final String HISTORY_ACTOR =
            "db/migration/payments/V006__history_actor_admits_the_platform.sql";
    /** The refund edge trigger's CURRENT definition (V004 is applied history). */
    private static final String REFUND_PERMIT =
            "db/migration/payments/V009__refund_carries_its_send_permit.sql";
    /** V011: the rail joined the birth facts (V003 is applied history). */
    private static final String ATTEMPT_RAIL =
            "db/migration/payments/V011__the_attempt_records_its_rail.sql";
    /** The attempt AND intent triggers' CURRENT definitions (P7-TSK-002). */
    private static final String MODEL_MACHINES =
            "db/migration/payments/V012__the_machines_per_interaction_model.sql";

    @Test
    @DisplayName("the intent's status CHECKs are generated from the machine, on all three columns")
    void intentStatusChecksMatchTheEnum() {
        String list = PaymentIntentStatus.sqlValueList();
        assertThat(migration(INTENT))
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("the attempt's status CHECKs are generated from the machines - V012 holds the"
            + " current definitions (V003 is applied history)")
    void attemptStatusChecksMatchTheEnum() {
        // V003 was written against the seven-state vocabulary; the enum widened at
        // P7-TSK-002, so the reconciliation follows the constraints to V012: the row's
        // status binds vocabulary AND model in one CHECK, each list generated from
        // InteractionModel.sqlStatusList(), and the history's from/to take the whole
        // vocabulary (edge legality is the trigger's, and the models' vocabularies bind
        // each other on the row).
        String all = PaymentAttemptStatus.sqlValueList();
        assertThat(migration(MODEL_MACHINES))
                .contains("CHECK (interaction_model IN (" + InteractionModel.sqlValueList()
                        + "))")
                .contains("CHECK (from_status IN (" + all + "))")
                .contains("CHECK (to_status IN (" + all + "))");
        for (InteractionModel model : InteractionModel.values()) {
            assertThat(migration(MODEL_MACHINES))
                    .contains("(interaction_model = '" + model.name() + "'")
                    .contains("status IN (" + model.sqlStatusList() + ")");
        }
    }

    @Test
    @DisplayName("the refund's status CHECKs are generated from the machine, on all three columns")
    void refundStatusChecksMatchTheEnum() {
        String list = RefundStatus.sqlValueList();
        assertThat(migration(REFUND))
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("the failure-reason CHECK is generated from PaymentFailureReason - V007 holds"
            + " the current definition (V003 is applied history)")
    void failureReasonCheckMatchesTheEnum() {
        // P5-TSK-014 widened the enum with the sweeper's NEVER_RECEIVED; V003 cannot be
        // edited (ADR-0011, forward-only), so V007 REPLACES the constraint and this
        // reconciliation follows the constraint to its current definition.
        assertThat(migration(
                        "db/migration/payments/V007__the_sweepers_failure_reason.sql"))
                .contains("CHECK (failure_reason IN ("
                        + PaymentFailureReason.sqlValueList() + "))");
    }

    @Test
    @DisplayName("every transition trigger's edge conditions are generated from its machine")
    void triggerEdgesMatchTheMachines() {
        assertEdges(migration(INTENT), PaymentIntentStatus.values().length,
                java.util.Arrays.stream(PaymentIntentStatus.values())
                        .collect(Collectors.toMap(Enum::name, s -> s.permittedTransitions()
                                .stream().map(Enum::name).collect(Collectors.toList()))));
        // The attempt's V003 and V011 functions are applied history holding the two-step
        // machine; V012 holds the current, model-keyed definition (P7-TSK-002). Each file's
        // conditions are generated from the machine that was current when it was written -
        // InteractionModel.TWO_STEP's, which is Phase 5's verbatim.
        assertEdges(migration(ATTEMPT), InteractionModel.TWO_STEP.statuses().size(),
                twoStepEdgeNames());
        assertEdges(migration(REFUND), RefundStatus.values().length,
                java.util.Arrays.stream(RefundStatus.values())
                        .collect(Collectors.toMap(Enum::name, s -> s.permittedTransitions()
                                .stream().map(Enum::name).collect(Collectors.toList()))));
        // V009 REPLACED the refund's function (the Phase 6 -> 7 transition's send permit): the
        // current definition must carry the machine's edges too, or it could drift from the
        // enum while V004 - applied history - still matched.
        assertEdges(migration(REFUND_PERMIT), RefundStatus.values().length,
                java.util.Arrays.stream(RefundStatus.values())
                        .collect(Collectors.toMap(Enum::name, s -> s.permittedTransitions()
                                .stream().map(Enum::name).collect(Collectors.toList()))));
        // V011 REPLACED the attempt's function (P7-TSK-001: the rail joined the birth facts):
        // the current definition must carry the machine's edges too, or it could drift from
        // the enum while V003 - applied history - still matched.
        assertEdges(migration(ATTEMPT_RAIL), InteractionModel.TWO_STEP.statuses().size(),
                twoStepEdgeNames());
        // V012: each model's own edges, in the one model-keyed disjunction.
        for (InteractionModel model : InteractionModel.values()) {
            assertEdges(migration(MODEL_MACHINES), model.statuses().size(),
                    model.edges().entrySet().stream()
                            .collect(Collectors.toMap(entry -> entry.getKey().name(),
                                    entry -> entry.getValue().stream().map(Enum::name)
                                            .collect(Collectors.toList()))));
        }
    }

    private static java.util.Map<String, java.util.List<String>> twoStepEdgeNames() {
        return InteractionModel.TWO_STEP.edges().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(),
                        entry -> entry.getValue().stream().map(Enum::name)
                                .collect(Collectors.toList())));
    }

    @Test
    @DisplayName("the attempt's rail is backfilled as the card rail, NOT NULL, shape-checked,"
            + " and frozen among the birth facts (V011)")
    void theAttemptRailClausesAreInTheCurrentFunction() {
        assertThat(migration(ATTEMPT_RAIL))
                .contains("CREATE OR REPLACE FUNCTION"
                        + " payments.payment_attempt_permits_only_machine_edges()")
                .contains("OR OLD.rail <> NEW.rail")
                .contains(
                        "DISABLE TRIGGER payment_attempt_permits_only_machine_edges")
                .contains(
                        "ENABLE TRIGGER payment_attempt_permits_only_machine_edges")
                .contains("ALTER COLUMN rail SET NOT NULL")
                .contains("CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$')")
                // The backfill records history rather than guessing: every existing attempt
                // was dispatched on the one rail the platform ever had (ADR-0049) - and the
                // literal IS the adapter's declaration, so the two cannot drift apart.
                .contains("UPDATE payments.payment_attempt SET rail = '"
                        + SimulatedCardPspAdapter.RAIL.id().value() + "';");
    }

    @Test
    @DisplayName("the model and capture-mode birth facts are backfilled, NOT NULL, frozen for"
            + " every writer, and the credit account is frozen under its new name (V012)")
    void theModelAndCaptureModeClausesAreInTheCurrentFunctions() {
        assertThat(migration(MODEL_MACHINES))
                // The attempt's half: the backfill runs under the disabled trigger (recording
                // history, not moving a machine), then the model joins the frozen birth facts.
                .contains("DISABLE TRIGGER payment_attempt_permits_only_machine_edges")
                .contains("UPDATE payments.payment_attempt SET interaction_model = 'TWO_STEP';")
                .contains("ENABLE TRIGGER payment_attempt_permits_only_machine_edges")
                .contains("ALTER COLUMN interaction_model SET NOT NULL")
                .contains("OR OLD.interaction_model <> NEW.interaction_model")
                // auth_reference is the two-step model's fact now: nullable on the row, bound
                // to the model by one CHECK each way, its freeze surviving nullability.
                .contains("ALTER COLUMN auth_reference DROP NOT NULL")
                .contains(
                        "ADD CONSTRAINT payment_attempt_two_step_carries_its_dispatch_reference")
                .contains(
                        "ADD CONSTRAINT payment_attempt_foreign_model_carries_no_two_step_facts")
                .contains("OR OLD.auth_reference IS DISTINCT FROM NEW.auth_reference")
                // The intent's half: the same backfill discipline, the mode's CHECK generated
                // from the enum, and the renamed credit account frozen under its new name -
                // the rename lands HERE because a plpgsql body does not follow renames
                // (the P6-TSK-005 debt, paid).
                .contains("DISABLE TRIGGER payment_intent_permits_only_machine_edges")
                .contains("UPDATE payments.payment_intent SET capture_mode = 'AUTOMATIC';")
                .contains("ENABLE TRIGGER payment_intent_permits_only_machine_edges")
                .contains("ALTER COLUMN capture_mode SET NOT NULL")
                .contains("CHECK (capture_mode IN (" + CaptureMode.sqlValueList() + "))")
                .contains("RENAME COLUMN wallet_account_id TO credit_account_id")
                .contains("OLD.credit_account_id <> NEW.credit_account_id")
                .contains("OR OLD.capture_mode <> NEW.capture_mode");
    }

    @Test
    @DisplayName("the in-flight index's predicate is the intent machine's own non-terminal set"
            + " (V010)")
    void theInFlightIndexPredicateIsGeneratedFromTheMachine() {
        assertThat(migration("db/migration/payments/V010__intents_in_flight_by_credit_account.sql"))
                .contains("CREATE INDEX payment_intent_in_flight_by_credit_account")
                .contains("WHERE status NOT IN ("
                        + PaymentIntentStatus.sqlTerminalValueList() + ")");
    }

    @Test
    @DisplayName("the refund's send permit is forward-only, only while resolvable, and the"
            + " replaced function still freezes the dispatch (V009)")
    void theRefundPermitClausesAreInTheCurrentFunction() {
        assertThat(migration(REFUND_PERMIT))
                .contains("CREATE OR REPLACE FUNCTION payments.refund_permits_only_machine_edges()")
                .contains("IF NEW.last_dispatched_at < OLD.last_dispatched_at THEN")
                .contains("AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') THEN")
                .contains("OR OLD.dispatch_key IS DISTINCT FROM NEW.dispatch_key THEN")
                .contains("CHECK (last_dispatched_at >= created_at)")
                .contains("GRANT UPDATE (last_dispatched_at) ON payments.refund TO finapp_app;");
    }

    @Test
    @DisplayName("the one-live-attempt predicate is generated from the terminal list - V012"
            + " holds the current definition (EXECUTED joined the terminals, P7-TSK-002)")
    void oneLiveAttemptPredicateMatchesTheTerminals() {
        assertThat(migration(MODEL_MACHINES))
                .contains("CREATE UNIQUE INDEX payment_attempt_one_live_per_intent")
                .contains("WHERE status NOT IN ("
                        + PaymentAttemptStatus.sqlTerminalValueList() + ")");
    }

    @Test
    @DisplayName("the monetary shapes are MoneyColumns fragments, verbatim - two of them nullable")
    void monetaryShapesAreTheGeneratedFragments() {
        assertThat(migration(INTENT))
                .contains(new MoneyColumns.ColumnNames("amount_minor", "currency", "scale")
                        .ddl());
        assertThat(migration(REFUND))
                .contains(new MoneyColumns.ColumnNames("amount_minor", "currency", "scale")
                        .ddl());
        // The attempt's amounts arrive with later transitions, so the fragments are the
        // NULLABLE derivation - same one definition, presence rules carried by the stage CHECK.
        assertThat(migration(ATTEMPT))
                .contains(MoneyColumns.columnsFor("authorized").nullableDdl())
                .contains(MoneyColumns.columnsFor("captured").nullableDdl());
    }

    @Test
    @DisplayName("the reference shape CHECKs are generated from the reference types' own bounds")
    void referenceShapesMatchTheTypes() {
        String idempotency = "~ '^[A-Za-z0-9-]{1," + ProviderIdempotencyReference.MAX_LENGTH
                + "}$'";
        String provider = "~ '^[A-Za-z0-9_.:-]{1," + ProviderReference.MAX_LENGTH + "}$'";
        assertThat(migration(ATTEMPT))
                .contains("CHECK (auth_reference " + idempotency + ")")
                .contains("CHECK (capture_reference " + idempotency + ")")
                .contains("CHECK (auth_provider_reference " + provider + ")")
                .contains("CHECK (capture_provider_reference " + provider + ")");
        assertThat(migration(REFUND))
                .contains("CHECK (provider_idempotency_reference " + idempotency + ")")
                .contains("CHECK (provider_reference " + provider + ")");
    }

    @Test
    @DisplayName("the refund reason bound is Refund.MAX_REASON_LENGTH")
    void refundReasonBoundMatchesTheConstant() {
        assertThat(migration(REFUND))
                .contains("CHECK (length(reason) <= " + Refund.MAX_REASON_LENGTH + ")");
    }

    @Test
    @DisplayName("the evidence size bound is PspWireClient.MAX_EVIDENCE_BYTES, and the GCM shape holds")
    void evidenceBoundsMatchTheWireClient() {
        assertThat(migration(EVIDENCE))
                .contains("CHECK (content_length BETWEEN 1 AND "
                        + PspWireClient.MAX_EVIDENCE_BYTES + ")")
                .contains("CHECK (octet_length(content_ciphertext) = content_length + 16)")
                .contains("CHECK (octet_length(content_nonce) = 12)")
                .contains("CHECK (octet_length(checksum_sha256) = 32)");
    }

    @Test
    @DisplayName("the refund bound trigger holds its registered advisory-lock namespace")
    void refundBoundTakesTheRegisteredNamespace() {
        // Namespace 3, registered in DISTRIBUTED_EXECUTION.md beside the relay's (1) and
        // V009's (2). A migration quietly changing the namespace would collide with a sibling
        // component silently, and only under load.
        assertThat(migration(REFUND))
                .contains("pg_advisory_xact_lock(3, hashtext(NEW.attempt_id::text))");
    }

    @Test
    @DisplayName("the grants are the planned sets - the intent's UPDATE is ONE column")
    void grantsAreTheColumnNarrowedSets() {
        assertThat(migration(INTENT))
                .contains("GRANT SELECT, INSERT ON payments.payment_intent TO finapp_app;")
                // The aggregate's nothing-but-status-changes assertion, at the privilege.
                .contains("GRANT UPDATE (status) ON payments.payment_intent TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON payments.payment_intent_event"
                        + " TO finapp_app;")
                .doesNotContain("GRANT DELETE");
        assertThat(migration(ATTEMPT))
                .contains("GRANT SELECT, INSERT ON payments.payment_attempt TO finapp_app;")
                // Status plus exactly the payload columns the doors carry - the aggregate's
                // recorded wider-grant statement, reconciled rather than rediscovered.
                .contains("GRANT UPDATE (status, capture_reference, auth_provider_reference,"
                        + " capture_provider_reference, authorized_amount_minor,"
                        + " authorized_currency, authorized_scale, captured_amount_minor,"
                        + " captured_currency, captured_scale, failure_reason)"
                        + " ON payments.payment_attempt TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON payments.payment_attempt_event"
                        + " TO finapp_app;")
                .doesNotContain("GRANT DELETE");
        assertThat(migration(REFUND))
                .contains("GRANT SELECT, INSERT ON payments.refund TO finapp_app;")
                .contains("GRANT UPDATE (status, provider_reference) ON payments.refund"
                        + " TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON payments.refund_event TO finapp_app;")
                .doesNotContain("GRANT DELETE");
        // Evidence: SELECT and INSERT and nothing else, plus the every-writer trigger.
        assertThat(migration(EVIDENCE))
                .contains("GRANT SELECT, INSERT ON payments.provider_evidence TO finapp_app;")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE")
                .contains("BEFORE UPDATE OR DELETE ON payments.provider_evidence");
    }

    @Test
    @DisplayName("the evidence kind CHECK matches the enum that arrived with its first writer")
    void evidenceKindMatchesTheEnum() {
        // V005's hand-listed CHECK, reconciled from the moment EvidenceKind exists
        // (P5-TSK-009) - the applied migration is history; this asserts the enum grew INTO
        // the schema's list, not past it.
        assertThat(migration(EVIDENCE))
                .contains("CHECK (kind IN (" + EvidenceKind.sqlValueList() + "))");
    }

    @Test
    @DisplayName("V006 moves the histories to the audit actor model, bounded like audit_record")
    void historyActorModelMatchesTheAuditTable() {
        String v006 = migration(HISTORY_ACTOR);
        for (String table : new String[] {
                "payment_intent_event", "payment_attempt_event", "refund_event"}) {
            assertThat(v006)
                    .contains("ALTER TABLE payments." + table
                            + "\n    ALTER COLUMN actor_id TYPE text USING actor_id::text;")
                    .contains("ALTER TABLE payments." + table
                            + "\n    ADD COLUMN actor_type text NOT NULL;")
                    .contains("CHECK (length(actor_id) BETWEEN 1 AND 200)")
                    .contains("CHECK (length(actor_type) BETWEEN 1 AND 50)");
        }
    }

    @Test
    @DisplayName("the guard can actually read every migration")
    void theGuardIsNotVacuous() {
        assertThat(migration(INTENT)).contains("CREATE TABLE payments.payment_intent (");
        assertThat(migration(ATTEMPT)).contains("CREATE TABLE payments.payment_attempt (");
        assertThat(migration(REFUND)).contains("CREATE TABLE payments.refund (");
        assertThat(migration(EVIDENCE)).contains("CREATE TABLE payments.provider_evidence (");
    }

    /**
     * The {@code TransferMigrationTest#triggerEdgesMatchTheMachine} assertion, per machine: a
     * non-terminal state's exact edge set as the trigger writes it (target order is
     * {@code permittedTransitions()}'s own iteration order), and a terminal state appearing in
     * NO edge condition as a source ({@code INV-LIFE-04}).
     */
    private static void assertEdges(
            String migration,
            int stateCount,
            java.util.Map<String, java.util.List<String>> edges) {
        assertThat(edges).hasSize(stateCount);
        for (var entry : edges.entrySet()) {
            if (entry.getValue().isEmpty()) {
                assertThat(migration)
                        .as("terminal %s must be no edge condition's source", entry.getKey())
                        .doesNotContain("OLD.status = '" + entry.getKey() + "'");
                continue;
            }
            String condition = "(OLD.status = '" + entry.getKey() + "' AND NEW.status IN ("
                    + entry.getValue().stream()
                            .map(to -> "'" + to + "'")
                            .collect(Collectors.joining(", "))
                    + "))";
            assertThat(migration)
                    .as("the trigger must carry %s's exact edge set", entry.getKey())
                    .contains(condition);
        }
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration(String path) {
        try (InputStream migration =
                PaymentsMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + path);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
