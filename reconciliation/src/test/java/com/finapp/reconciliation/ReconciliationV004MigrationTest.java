package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V004` reconciled against the vocabulary that generates it (`P8-TSK-010`, the `V002`
 * discipline): the break's type, cause, status, severity and classification {@code CHECK}s
 * are the enums' value lists; the transition triggers' edges are the machines'
 * {@code permittedTransitions()}; the severity forward-only rule and the raise-time
 * type–cause pairing are {@link Severity#sqlForwardOnlyRule()} and
 * {@link BreakCause#sqlRaisePairingRule()}; the suspense owner-type list is
 * {@link BreakType#sqlSuspenseOwningList()}; and the arbiters and the no-DELETE floor are
 * written as designed.
 */
@DisplayName("reconciliation V004 reconciliation (P8-TSK-010)")
class ReconciliationV004MigrationTest {

    private static final String V004 =
            "db/migration/reconciliation/V004__breaks_and_suspense_as_records.sql";

    @Test
    @DisplayName("the break's value lists, machine edges, severity forward rule and"
            + " raise-time pairing are the enums'")
    void theBreakMachineIsGenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT break_type CHECK (type IN ("
                                + BreakType.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT break_cause CHECK (cause IN ("
                                + BreakCause.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT break_status CHECK (status IN ("
                                + BreakStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT break_severity CHECK (severity IN ("
                                + Severity.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT break_event_type CHECK (event_type IN ("
                                + BreakEventType.sqlValueList() + "))"))
                .contains(BreakStatus.sqlTransitionRule())
                .contains(Severity.sqlForwardOnlyRule())
                .contains(BreakCause.sqlRaisePairingRule());
    }

    @Test
    @DisplayName("the suspense item's and release's value lists, machine edges and"
            + " owner-type list are the enums'")
    void theSuspenseMachineIsGenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT suspense_item_origin CHECK (origin IN ("
                                + SuspenseOrigin.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT suspense_item_side CHECK (side IN ("
                                + SuspenseSide.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT suspense_item_status CHECK (status IN ("
                                + SuspenseItemStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT suspense_release_cause CHECK (cause IN ("
                                + ReleaseCause.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT park_kind CHECK (kind IN ("
                                + ParkKind.sqlValueList() + "))"))
                .contains(SuspenseItemStatus.sqlTransitionRule())
                .contains(normalized(
                        "IF owner_type NOT IN (" + BreakType.sqlSuspenseOwningList() + ")"));
    }

    @Test
    @DisplayName("the arbiters, the ownership and the no-DELETE floor are written as"
            + " designed - the refusing trigger on every table")
    void theArbitersAndTheFloorStand() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "ON reconciliation.break (type, expectation_id)"
                                + " WHERE status <> 'RESOLVED'"))
                .contains(normalized(
                        "ON reconciliation.break (type, external_item_id)"
                                + " WHERE status <> 'RESOLVED'"))
                .contains(normalized(
                        "ON reconciliation.break (type, suspense_item_id)"
                                + " WHERE status <> 'RESOLVED'"))
                .contains(normalized(
                        "ON reconciliation.break (type, run_id)"
                                + " WHERE status <> 'RESOLVED'"))
                .contains("break_id UUID NOT NULL")
                .contains(normalized(
                        "CONSTRAINT suspense_item_external_item_once UNIQUE"
                                + " (external_item_id)"))
                .contains("CONSTRAINT suspense_item_origin_once UNIQUE (origin_ref)")
                .contains(normalized(
                        "CONSTRAINT suspense_item_release_counted CHECK ("
                                + " released_minor >= 0 AND released_minor <="
                                + " amount_minor)"))
                .contains("CONSTRAINT park_entry_once UNIQUE (journal_entry_id)")
                .contains(normalized(
                        "CONSTRAINT break_resolved_iff_dated CHECK ((status = 'RESOLVED')"
                                + " = (resolved_at IS NOT NULL))"));
        // The note's screens hold at this rank for every writer (INV-PAY-02, INV-RAIL-03).
        assertThat(sql)
                .contains("NOT reconciliation.holds_luhn_valid_digit_run(body)")
                .contains(normalized(
                        "CONSTRAINT break_note_body_bounded CHECK (char_length(body)"
                                + " BETWEEN 1 AND 4000)"));
        // The floor: no DELETE is grantable, and the refusing trigger stands beneath the
        // absent grant on every table - the migrator included.
        assertThat(sql).doesNotContain("GRANT DELETE");
        assertThat(sql)
                .contains("BEFORE DELETE ON reconciliation.break")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.break_event")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.break_note")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.break_evidence_link")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.park")
                .contains("BEFORE DELETE ON reconciliation.suspense_item")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.suspense_release");
    }

    // -----------------------------------------------------------------

    /** Whitespace collapsed, so a generated fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV004MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V004)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V004);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + V004, e);
        }
    }
}
