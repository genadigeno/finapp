package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V003` reconciled against the vocabulary that generates it (`P8-TSK-009`, the `V002`
 * discipline): the run's and item's status, kind and key {@code CHECK}s are the enums' value
 * lists, both transition triggers' edge conditions are exactly the machines'
 * {@code permittedTransitions()} — stated WHOLE while only birth is produced — and the
 * identity arbiters are written as designed.
 */
@DisplayName("reconciliation V003 reconciliation (P8-TSK-009)")
class ReconciliationV003MigrationTest {

    private static final String V003 =
            "db/migration/reconciliation/V003__runs_and_external_items.sql";

    @Test
    @DisplayName("the run's status and kind CHECKs and its trigger edges are the enums'")
    void theRunMachineIsGenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT reconciliation_batch_status CHECK (status IN ("
                                + RunStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT reconciliation_batch_kind CHECK (kind IN ("
                                + RunKind.sqlValueList() + "))"))
                .contains(RunStatus.sqlTransitionRule());
    }

    @Test
    @DisplayName("the item's status, line-type and key-kind CHECKs and its trigger edges are"
            + " the enums' - the repudiation's reopening edge included, stated now")
    void theItemMachineIsGenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT external_item_status CHECK (status IN ("
                                + ItemStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT external_item_line_type CHECK (line_type IN ("
                                + ExternalLineType.sqlValueList(ExternalLineType.reportVocabulary()) + "))"))
                .contains(normalized(
                        "CONSTRAINT external_item_key_kind CHECK (key_kind IN ("
                                + ItemKeyKind.sqlValueList(ItemKeyKind.reportVocabulary()) + "))"))
                .contains(ItemStatus.sqlTransitionRule());
    }

    @Test
    @DisplayName("the identity arbiters are written as designed, and finapp_app gets no"
            + " DELETE anywhere")
    void theArbitersAndTheFloorStand() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CONSTRAINT reconciliation_batch_batch_once UNIQUE (batch_id)")
                .contains(normalized(
                        "ON reconciliation.reconciliation_batch (source_id, source_sequence)"
                                + " WHERE kind = 'BATCH'"))
                .contains(normalized(
                        "ON reconciliation.reconciliation_batch (source_id)"
                                + " WHERE kind = 'REPROCESS' AND status <> 'COMPLETED'"))
                .contains("CONSTRAINT external_item_line_once UNIQUE (settlement_line_id)")
                .contains(normalized(
                        "CONSTRAINT external_item_value_conserved CHECK ("
                                + " allocated_minor >= 0 AND parked_minor >= 0 AND"
                                + " offset_minor >= 0 AND allocated_minor + parked_minor +"
                                + " offset_minor <= amount_minor)"));
        // The floor: no DELETE is grantable here - the only DELETE tokens are the
        // append-only and never-deleted triggers' own.
        assertThat(sql).doesNotContain("GRANT DELETE");
        assertThat(sql)
                .contains("BEFORE DELETE ON reconciliation.reconciliation_batch")
                .contains("BEFORE DELETE ON reconciliation.external_item")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.external_item_key");
    }

    // -----------------------------------------------------------------

    /** Whitespace collapsed, so a generated fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV003MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V003)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V003);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
