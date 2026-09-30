package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V005` reconciled against the vocabulary that generates it (`P8-TSK-011`, the `V002`
 * discipline): the decision's origin, outcome, strategy and key-kind {@code CHECK}s are the
 * enums' value lists; the allocation unique, the deferred Σ triggers on BOTH sides, the
 * run-completion guard and the append-only floor are written as designed; and the
 * expectation history's regenerated event list carries exactly the producer this migration
 * brings.
 */
@DisplayName("reconciliation V005 reconciliation (P8-TSK-011)")
class ReconciliationV005MigrationTest {

    private static final String V005 =
            "db/migration/reconciliation/V005__match_decisions_candidates_and_allocations.sql";

    @Test
    @DisplayName("the decision's value lists are the enums'")
    void theDecisionVocabularyIsGenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT match_decision_origin CHECK (origin IN ("
                                + DecisionOrigin.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT match_decision_outcome CHECK (outcome IN ("
                                + DecisionOutcome.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT match_decision_strategy CHECK (strategy IS NULL OR"
                                + " strategy IN (" + Cardinality.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT match_decision_key_kind CHECK (matched_key_kind IS"
                                + " NULL OR matched_key_kind IN ("
                                + KeyKind.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT match_candidate_key_kind CHECK (key_kind IN ("
                                + KeyKind.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT match_candidate_direction CHECK (direction IN ("
                                + Arrays.stream(ExpectationDirection.values())
                                        .map(value -> "'" + value.name() + "'")
                                        .collect(Collectors.joining(", "))
                                + "))"));
    }

    @Test
    @DisplayName("the arbiters and the Σ discipline are written as designed: the one-pair"
            + " unique, both deferred sum sides, and the run-completion guard")
    void theArbitersStand() {
        String sql = normalized(migration());
        assertThat(sql)
                // INV-REC-07's structural half: one positive allocation per pair.
                .contains(normalized(
                        "CREATE UNIQUE INDEX allocation_pair_once ON"
                                + " reconciliation.allocation (external_item_id,"
                                + " expectation_id) WHERE reverses_allocation_id IS NULL"))
                .contains("CONSTRAINT allocation_amount_positive CHECK (amount_minor > 0)")
                // The Σ fires from the allocation AND from either denormalised column, so a
                // writer can neither allocate without recording nor record without allocating.
                .contains(normalized(
                        "CREATE CONSTRAINT TRIGGER allocation_sums_reconcile AFTER INSERT"
                                + " ON reconciliation.allocation DEFERRABLE INITIALLY"
                                + " DEFERRED"))
                .contains(normalized(
                        "CREATE CONSTRAINT TRIGGER item_allocated_column_reconciles AFTER"
                                + " UPDATE OF allocated_minor ON"
                                + " reconciliation.external_item DEFERRABLE INITIALLY"
                                + " DEFERRED"))
                .contains(normalized(
                        "CREATE CONSTRAINT TRIGGER expectation_allocated_column_reconciles"
                                + " AFTER UPDATE OF allocated_minor ON"
                                + " reconciliation.expectation DEFERRABLE INITIALLY"
                                + " DEFERRED"))
                // A run never completes over an undisposed item, for every writer.
                .contains(normalized(
                        "CREATE CONSTRAINT TRIGGER run_completes_only_disposed AFTER"
                                + " UPDATE OF status ON"
                                + " reconciliation.reconciliation_batch DEFERRABLE"
                                + " INITIALLY DEFERRED"))
                // The decision-subject foreign key V004 deliberately left off.
                .contains(normalized(
                        "ADD CONSTRAINT break_decision_fk FOREIGN KEY (decision_id)"
                                + " REFERENCES reconciliation.match_decision (id)"));
    }

    @Test
    @DisplayName("decisions, candidates and allocations are history for every writer: no"
            + " UPDATE or DELETE grantable, the refusing trigger beneath")
    void theAppendOnlyFloorStands() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.match_decision")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.match_candidate")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.allocation")
                .contains("GRANT SELECT, INSERT ON reconciliation.match_decision")
                .contains("GRANT SELECT, INSERT ON reconciliation.match_candidate")
                .contains("GRANT SELECT, INSERT ON reconciliation.allocation");
        assertThat(sql).doesNotContain("GRANT DELETE");
        assertThat(sql).doesNotContain("GRANT UPDATE");
    }

    @Test
    @DisplayName("the expectation history's regenerated list carries exactly the new"
            + " producer - OPENED, KEY_COLLISION and now ALLOCATED")
    void theExpectationEventListIsRegenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT expectation_event_type CHECK (event_type IN ("
                                + "'OPENED', 'KEY_COLLISION', 'ALLOCATED'))"));
    }

    // -----------------------------------------------------------------

    /** Whitespace collapsed, so a generated fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV005MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V005)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V005);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + V005, e);
        }
    }
}
