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
    @DisplayName("the attempt's status CHECKs are generated from the machine, on all three columns")
    void attemptStatusChecksMatchTheEnum() {
        String list = PaymentAttemptStatus.sqlValueList();
        assertThat(migration(ATTEMPT))
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
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
    @DisplayName("the failure-reason CHECK is generated from PaymentFailureReason")
    void failureReasonCheckMatchesTheEnum() {
        assertThat(migration(ATTEMPT))
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
        assertEdges(migration(ATTEMPT), PaymentAttemptStatus.values().length,
                java.util.Arrays.stream(PaymentAttemptStatus.values())
                        .collect(Collectors.toMap(Enum::name, s -> s.permittedTransitions()
                                .stream().map(Enum::name).collect(Collectors.toList()))));
        assertEdges(migration(REFUND), RefundStatus.values().length,
                java.util.Arrays.stream(RefundStatus.values())
                        .collect(Collectors.toMap(Enum::name, s -> s.permittedTransitions()
                                .stream().map(Enum::name).collect(Collectors.toList()))));
    }

    @Test
    @DisplayName("the one-live-attempt predicate is generated from the terminal list")
    void oneLiveAttemptPredicateMatchesTheTerminals() {
        assertThat(migration(ATTEMPT))
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
