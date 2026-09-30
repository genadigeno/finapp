package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V006` reconciled against its governance (`P8-TSK-012`, ADR-0071; the plan's §8 slice):
 * the {@code CHECK}s deliberately admit only the PRODUCED subset — kind {@code EVIDENCED},
 * status {@code APPROVED}, reason {@code EVIDENCE_RECEIVED} — a narrowed RANK, not a
 * narrowed vocabulary (the enums state the whole sets `V007` regenerates from, and this
 * test pins that each narrowed list is a subset of its enum's). The platform-only rule,
 * the distinctness rule, the uniques and the every-writer append-only floor are written
 * as designed.
 */
@DisplayName("reconciliation V006 reconciliation (P8-TSK-012)")
class ReconciliationV006MigrationTest {

    private static final String V006 =
            "db/migration/reconciliation/V006__resolution_evidenced.sql";

    @Test
    @DisplayName("the narrowed lists admit exactly the produced subset, each a member of"
            + " its stated-whole enum")
    void theNarrowedListsAreTheProducedSubset() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CONSTRAINT resolution_kind CHECK (kind IN ('EVIDENCED'))")
                .contains("CONSTRAINT resolution_status CHECK (status IN ('APPROVED'))")
                .contains(normalized(
                        "CONSTRAINT resolution_reason_code CHECK (reason_code IN"
                                + " ('EVIDENCE_RECEIVED'))"));
        // The rank is narrowed, never the vocabulary: every admitted value is the
        // enum's, and V007 regenerates the widened lists from these same enums.
        assertThat(ResolutionKind.sqlValueList()).contains("'EVIDENCED'");
        assertThat(ResolutionStatus.sqlValueList()).contains("'APPROVED'");
        assertThat(ResolutionReasonCode.sqlValueList()).contains("'EVIDENCE_RECEIVED'");
        assertThat(ResolutionStatus.APPROVED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("EVIDENCED is the platform's alone, born decided; distinctness and the"
            + " one-live-proposal arbiter are stated; the uniques stand")
    void theAuthorityRulesAreWritten() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT resolution_evidenced_is_platform CHECK ("
                                + " kind <> 'EVIDENCED' OR (proposed_by_type = 'SYSTEM'"
                                + " AND status = 'APPROVED' AND NOT four_eyes))"))
                .contains(normalized(
                        "CONSTRAINT resolution_four_eyes_distinct CHECK ("
                                + " status <> 'APPROVED' OR NOT four_eyes OR"
                                + " decided_by <> proposed_by)"))
                .contains(normalized(
                        "CREATE UNIQUE INDEX resolution_one_proposed_per_break"
                                + " ON reconciliation.resolution (break_id)"
                                + " WHERE status = 'PROPOSED'"))
                .contains("CONSTRAINT resolution_proposal_once UNIQUE"
                        + " (adjustment_proposal_id)")
                .contains("CONSTRAINT resolution_entry_once UNIQUE (journal_entry_id)")
                .contains(normalized(
                        "CONSTRAINT resolution_narrative_bounded CHECK ("
                                + " char_length(narrative) BETWEEN 1 AND 1000)"))
                // The break-note screens, at the same rank (INV-PAY-02, INV-RAIL-03).
                .contains("NOT reconciliation.holds_luhn_valid_digit_run(narrative)");
    }

    @Test
    @DisplayName("resolutions and their history are append-only for every writer: no"
            + " UPDATE or DELETE grantable, the refusing trigger beneath")
    void theAppendOnlyFloorStands() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.resolution")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.resolution_event")
                .contains("GRANT SELECT, INSERT ON reconciliation.resolution")
                .contains("GRANT SELECT, INSERT ON reconciliation.resolution_event");
        assertThat(sql).doesNotContain("GRANT UPDATE");
        assertThat(sql).doesNotContain("GRANT DELETE");
    }

    // -----------------------------------------------------------------

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV006MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V006)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V006);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + V006, e);
        }
    }
}
