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
    /** V013: routing policy, decision and availability (P7-TSK-003). */
    private static final String ROUTING =
            "db/migration/payments/V013__routing_policy_decision_and_availability.sql";

    /** The intent trigger's CURRENT definition (P7-TSK-002); the attempt's moved on. */
    private static final String MODEL_MACHINES =
            "db/migration/payments/V012__the_machines_per_interaction_model.sql";

    /** The attempt trigger, model CHECK and one-live predicate's CURRENT definitions
     * (P7-TSK-004: the void joined the two-step machine). */
    private static final String VOID =
            "db/migration/payments/V014__the_card_void.sql";

    /** V015: the clearing evidence (P7-TSK-005) - insert-only, no machine, no money. */
    private static final String CLEARING =
            "db/migration/payments/V015__clearing_record.sql";

    /** V016: the wallet withdrawal (P7-TSK-008) - the payout machine on the wallet, the
     * evidence and routing subjects widened, routing version 2 seeded. */
    private static final String WITHDRAWAL =
            "db/migration/payments/V016__the_wallet_withdrawal.sql";

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
    @DisplayName("the attempt's status CHECKs are generated from the machines - V014 holds the"
            + " current definitions (V003, V011 and V012 are applied history)")
    void attemptStatusChecksMatchTheEnum() {
        // V012 bound vocabulary AND model in one CHECK, each list generated from what
        // InteractionModel.sqlStatusList() said THEN: the eleven-value vocabulary and the
        // seven-state two-step machine. The enum widened again at P7-TSK-004, so V012's
        // lists freeze here as applied history and the live reconciliation follows the
        // constraints to V014.
        String elevenValues = "'AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED',"
                + " 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED',"
                + " 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN',"
                + " 'EXECUTED'";
        assertThat(migration(MODEL_MACHINES))
                .contains("CHECK (interaction_model IN (" + InteractionModel.sqlValueList()
                        + "))")
                .contains("CHECK (from_status IN (" + elevenValues + "))")
                .contains("CHECK (to_status IN (" + elevenValues + "))")
                .contains("AND status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED',"
                        + " 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED')")
                .contains("AND status IN (" + InteractionModel.PUSH.sqlStatusList() + ")")
                .contains("AND status IN (" + InteractionModel.BOOK.sqlStatusList() + ")");
        // V014: the current definitions, generated from the live machine.
        String all = PaymentAttemptStatus.sqlValueList();
        assertThat(migration(VOID))
                .contains("CHECK (from_status IN (" + all + "))")
                .contains("CHECK (to_status IN (" + all + "))");
        for (InteractionModel model : InteractionModel.values()) {
            assertThat(migration(VOID))
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
        // machine as it stood when each was written - Phase 5's seven states, frozen
        // below: the live machine gained the void at P7-TSK-004, and an applied file
        // cannot follow it.
        assertEdges(migration(ATTEMPT), 7, phaseFiveTwoStepEdgeNames());
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
        assertEdges(migration(ATTEMPT_RAIL), 7, phaseFiveTwoStepEdgeNames());
        // V012's two-step disjunction is applied history - Phase 5's machine verbatim;
        // its push and book arms still match the live models, which have not widened.
        assertEdges(migration(MODEL_MACHINES), 7, phaseFiveTwoStepEdgeNames());
        assertEdges(migration(MODEL_MACHINES), InteractionModel.PUSH.statuses().size(),
                edgeNames(InteractionModel.PUSH));
        assertEdges(migration(MODEL_MACHINES), InteractionModel.BOOK.statuses().size(),
                edgeNames(InteractionModel.BOOK));
        // V014 REPLACED the function (P7-TSK-004: the void joined the two-step machine):
        // the current definition carries every model's live edges.
        for (InteractionModel model : InteractionModel.values()) {
            assertEdges(migration(VOID), model.statuses().size(), edgeNames(model));
        }
    }

    private static java.util.Map<String, java.util.List<String>> edgeNames(
            InteractionModel model) {
        return model.edges().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(),
                        entry -> entry.getValue().stream().map(Enum::name)
                                .collect(Collectors.toList())));
    }

    /**
     * Phase 5's seven-state two-step machine, frozen: V003, V011 and V012 are applied
     * history written against it. Target order is what {@code permittedTransitions()}
     * iterated when those files were generated - declaration order, then as now.
     */
    private static java.util.Map<String, java.util.List<String>> phaseFiveTwoStepEdgeNames() {
        return java.util.Map.of(
                "AUTH_DISPATCHED", java.util.List.of("AUTH_UNKNOWN", "AUTHORIZED", "FAILED"),
                "AUTH_UNKNOWN", java.util.List.of("AUTHORIZED", "FAILED"),
                "AUTHORIZED", java.util.List.of("CAPTURE_DISPATCHED"),
                "CAPTURE_DISPATCHED",
                        java.util.List.of("CAPTURE_UNKNOWN", "CAPTURED", "FAILED"),
                "CAPTURE_UNKNOWN", java.util.List.of("CAPTURED", "FAILED"),
                "CAPTURED", java.util.List.of(),
                "FAILED", java.util.List.of());
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
    @DisplayName("V013's routing CHECKs are generated from the enums, and the decision's"
            + " monetary snapshot is the MoneyColumns fragment (P7-TSK-003)")
    void routingChecksAreGeneratedFromTheEnums() {
        String directions = PaymentDirection.sqlValueList();
        String kinds = InstrumentKind.sqlValueList();
        assertThat(migration(ROUTING))
                // The rule's matchers and the decision's snapshot bind the same vocabularies.
                .contains("CHECK (direction IN (" + directions + "))")
                .contains("CHECK (instrument_kind IN (" + kinds + "))")
                .contains("CHECK (verdict IN (" + RoutingStepVerdict.sqlValueList() + "))")
                .contains("CHECK (rejection IN (" + RoutingRejection.sqlValueList() + "))")
                // The judged amount, snapshotted as the standard NOT NULL triple.
                .contains("amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL,"
                        + " scale SMALLINT NOT NULL")
                // The rule's optional ceiling: all-or-nothing as a triple, its own names.
                .contains("CHECK ((ceiling_amount_minor IS NULL) = (ceiling_currency IS NULL)"
                        + " AND (ceiling_amount_minor IS NULL) = (ceiling_scale IS NULL))");
        // Both direction CHECKs exist (rule and decision), and both kind CHECKs.
        assertThat(migration(ROUTING).split(
                        "CHECK \\(direction IN \\(" + directions + "\\)\\)", -1))
                .hasSize(3);
    }

    @Test
    @DisplayName("V013's clauses are pinned: immutability for every writer, the minting"
            + " arbiter, one chosen decision per payment, abandonment only on knowledge,"
            + " and the seeded version 1 (P7-TSK-003)")
    void theRoutingClausesArePinned() {
        assertThat(migration(ROUTING))
                // Immutable for every writer, the migrator included - all five tables.
                .contains("CREATE FUNCTION payments.routing_records_are_immutable()")
                .contains("CREATE TRIGGER routing_policy_version_is_immutable")
                .contains("CREATE TRIGGER routing_rule_is_immutable")
                .contains("CREATE TRIGGER routing_rule_rail_is_immutable")
                .contains("CREATE TRIGGER routing_decision_is_immutable")
                .contains("CREATE TRIGGER routing_decision_step_is_immutable")
                // The version-minting arbiter and its queue (the fee schedule's discipline).
                .contains("CONSTRAINT routing_policy_version_number_is_unique")
                .contains("UNIQUE (version)")
                // Effective forward - INV-HIST-04's keystone, the INV-MER-03 shape.
                .contains("CHECK (effective_from >= created_at)")
                // One CHOSEN decision per payment; refused explorations accumulate lawfully.
                .contains("CREATE UNIQUE INDEX routing_decision_one_chosen_per_intent")
                .contains("WHERE chosen_rail IS NOT NULL")
                // The step's own rules at DB rank: a rejection exactly when not chosen, and
                // abandonment only on knowledge (INV-RAIL-02 for every writer).
                .contains("CHECK ((verdict = 'CHOSEN') = (rejection IS NULL))")
                .contains("CHECK (verdict <> 'ABANDONED' OR rejection = 'NOTHING_SENT')")
                .contains("CHECK ((rejection = 'UNDECLARED_BY_BUILD') ="
                        + " (descriptor_version IS NULL))")
                // The seed: version 1 routes the card pay-in, or INV-HIST-04's NOT NULL
                // would refuse every confirmation on a fresh database. Textual pins, the
                // V011 reason: test databases are born after V013.
                .contains("VALUES ('019992e0-0000-7000-8000-000000000002', 0, 'card')")
                .contains("0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL")
                // The grants: no UPDATE and no DELETE anywhere but the availability fact.
                .contains("GRANT SELECT, INSERT, UPDATE ON payments.rail_availability"
                        + " TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON payments.routing_decision TO finapp_app;");
    }

    @Test
    @DisplayName("V014's void clauses are pinned: the two facts' shapes from the reference"
            + " types, uniqueness, the payload freeze for every writer, the stage shapes"
            + " with FAILED's three, the acknowledgement pair, and the narrowed grant"
            + " (P7-TSK-004)")
    void theVoidClausesArePinned() {
        assertThat(migration(VOID))
                // OUR reference and the provider's, each shaped by its own type's bound
                // (the auth_reference / auth_provider_reference split, V003's discipline).
                .contains("CHECK (void_reference ~ '^[A-Za-z0-9-]{1,"
                        + ProviderIdempotencyReference.MAX_LENGTH + "}$')")
                .contains("CHECK (void_provider_reference ~ '^[A-Za-z0-9_.:-]{1,"
                        + ProviderReference.MAX_LENGTH + "}$')")
                // One void, one acknowledgement, platform-wide - the capture's discipline.
                .contains("ADD CONSTRAINT payment_attempt_void_reference_is_unique"
                        + " UNIQUE (void_reference)")
                .contains("CONSTRAINT payment_attempt_void_provider_reference_is_unique")
                // The payload freeze admits the two new columns for EVERY writer.
                .contains("OR (OLD.void_reference IS NOT NULL AND NEW.void_reference"
                        + " IS DISTINCT FROM OLD.void_reference)")
                .contains("OR (OLD.void_provider_reference IS NOT NULL AND"
                        + " NEW.void_provider_reference IS DISTINCT FROM"
                        + " OLD.void_provider_reference)")
                // The stage shapes: a void state holds the promise and OUR reference and
                // never a captured pair; FAILED has exactly three legal shapes.
                .contains("WHEN 'VOID_DISPATCHED'    THEN authorized_amount_minor IS NOT NULL"
                        + " AND void_reference IS NOT NULL AND captured_amount_minor IS NULL")
                .contains("WHEN 'FAILED'             THEN (void_reference IS NOT NULL"
                        + " AND authorized_amount_minor IS NOT NULL)")
                .contains("OR (void_reference IS NULL AND (authorized_amount_minor IS NULL)"
                        + " = (capture_reference IS NULL))")
                // The acknowledgement exactly when VOIDED, both directions.
                .contains(
                        "ADD CONSTRAINT payment_attempt_void_ack_arrives_exactly_when_voided")
                .contains("CHECK ((status = 'VOIDED') = (void_provider_reference"
                        + " IS NOT NULL))")
                // The foreign-model wall re-ADDed with the void's columns in its list.
                .contains("AND void_reference IS NULL\n"
                        + "                AND void_provider_reference IS NULL))")
                // The app writes exactly the two new payload columns - no wider grant.
                .contains("GRANT UPDATE (void_reference, void_provider_reference)\n"
                        + "    ON payments.payment_attempt TO finapp_app;");
    }

    @Test
    @DisplayName("V015's clearing clauses are pinned: one record per attempt, one acquirer"
            + " reference platform-wide, the reference shapes from the type's bound,"
            + " append-only for every writer, and no grant beyond SELECT and INSERT"
            + " (P7-TSK-005)")
    void theClearingClausesArePinned() {
        assertThat(migration(CLEARING))
                .contains("CREATE TABLE payments.clearing_record (")
                .contains("CONSTRAINT clearing_record_one_per_attempt UNIQUE (attempt_id)")
                .contains("CONSTRAINT clearing_record_acquirer_reference_is_unique"
                        + " UNIQUE (acquirer_reference)")
                // Both references take the provider-reference shape, generated from the
                // type's own bound (the V003 discipline).
                .contains("CHECK (acquirer_reference ~ '^[A-Za-z0-9_.:-]{1,"
                        + ProviderReference.MAX_LENGTH + "}$')")
                .contains("CHECK (network_transaction_id ~ '^[A-Za-z0-9_.:-]{1,"
                        + ProviderReference.MAX_LENGTH + "}$')")
                // Append-only for every writer, the migrator included (INV-HIST-02's
                // regime): reconciliation's match keys are never edited.
                .contains("CREATE TRIGGER clearing_record_is_append_only")
                .contains("BEFORE UPDATE OR DELETE ON payments.clearing_record")
                .contains("GRANT SELECT, INSERT ON payments.clearing_record TO finapp_app;")
                .doesNotContain("GRANT UPDATE");
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
    @DisplayName("the one-live-attempt predicate is generated from the terminal list - V014"
            + " holds the current definition (VOIDED joined the terminals, P7-TSK-004)")
    void oneLiveAttemptPredicateMatchesTheTerminals() {
        // V012's index is applied history, written when the terminals were three.
        assertThat(migration(MODEL_MACHINES))
                .contains("CREATE UNIQUE INDEX payment_attempt_one_live_per_intent")
                .contains("WHERE status NOT IN ('CAPTURED', 'FAILED', 'EXECUTED')");
        assertThat(migration(VOID))
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

    @Test
    @DisplayName("V016's withdrawal clauses are generated from the enums and pinned"
            + " (P7-TSK-008)")
    void theWithdrawalClausesArePinned() {
        String migration = migration(WITHDRAWAL);
        assertThat(migration)
                .contains("CREATE TABLE payments.withdrawal (")
                // Generated lists, one definition each.
                .contains("CHECK (status IN (" + WithdrawalStatus.sqlValueList() + "))")
                .contains(
                        "CHECK (failure_reason IN ("
                                + WithdrawalFailureReason.sqlValueList()
                                + "))")
                // The coherence pairs, both directions.
                .contains("CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))")
                .contains("CHECK ((status = 'COMPLETED') = (scheme_reference IS NOT NULL))")
                .contains("CHECK (settlement_cycle IS NULL OR status = 'COMPLETED')")
                // Our reference: ISO 20022's own bound, unique platform-wide.
                .contains("end_to_end_reference   text        NOT NULL UNIQUE")
                .contains("CHECK (end_to_end_reference ~ '^[A-Za-z0-9-]{1,"
                        + EndToEndReference.MAX_LENGTH + "}$')")
                // INV-RAIL-03's trio on the destination copy (paymentmethods V003's rules).
                .contains("CHECK (destination_reference ~ '^[A-Za-z0-9_.:-]{1,128}$')")
                .contains("CHECK (destination_reference !~ '^[0-9_.:-]+$')")
                .contains("AND destination_reference ~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}$'")
                // The permit and the takeover key.
                .contains("CHECK (last_dispatched_at >= created_at)")
                .contains("UNIQUE INDEX withdrawal_one_per_dispatch_key")
                .contains("ON payments.withdrawal (customer_id, dispatch_key)")
                // The machine's edges for every writer, generated from the enum.
                .contains("(OLD.status = 'DISPATCHED' AND NEW.status IN ('COMPLETED',"
                        + " 'FAILED', 'UNKNOWN'))")
                .contains("(OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED',"
                        + " 'FAILED'))")
                .contains("a withdrawal''s send permit only moves forward")
                .contains("a resolved withdrawal is never sent again")
                // The narrowed grant: outcomes and the permit, nothing else.
                .contains("GRANT UPDATE (status, failure_reason, scheme_reference,"
                        + " settlement_cycle, last_dispatched_at)")
                .doesNotContain("GRANT DELETE");
        // Every machine edge in the trigger IS the enum's (the exhaustive direction: no
        // extra edge can hide, because the disjunction is pinned above and the enum sweep
        // in WithdrawalTest holds the object half).
        for (WithdrawalStatus from : WithdrawalStatus.values()) {
            if (from.isTerminal()) {
                assertThat(migration)
                        .doesNotContain("(OLD.status = '" + from.name() + "' AND NEW.status");
            }
        }
    }

    @Test
    @DisplayName("V016 widens the evidence and routing subjects with exactly-one rules"
            + " (P7-TSK-008)")
    void theSecondSubjectsArePinned() {
        assertThat(migration(WITHDRAWAL))
                // The evidence trio: at most one subject, recreated as the current
                // definition (V005's rule, one wider).
                .contains("ADD COLUMN withdrawal_id uuid REFERENCES payments.withdrawal (id)")
                .contains("CHECK (num_nonnulls(attempt_id, refund_id, withdrawal_id) <= 1)")
                // Routing's XOR: exactly one subject, and the withdrawal's one-chosen twin.
                .contains("ALTER COLUMN intent_id DROP NOT NULL")
                .contains("CHECK ((intent_id IS NULL) <> (withdrawal_id IS NULL))")
                .contains("UNIQUE INDEX routing_decision_one_chosen_per_withdrawal")
                .contains("WHERE chosen_rail IS NOT NULL AND withdrawal_id IS NOT NULL")
                // The seed: version 2 carries the standing card route forward AND the
                // bank pay-out, so neither flow strands on a fresh database.
                .contains("VALUES\n    ('019992e0-0000-7000-8000-000000000010', 2,")
                .contains("0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL)")
                .contains("1, 'PAY_OUT', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL)")
                .contains("('019992e0-0000-7000-8000-000000000011', 0, 'card')")
                .contains("('019992e0-0000-7000-8000-000000000012', 0, 'instant')");
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
