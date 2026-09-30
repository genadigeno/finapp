package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V007` reconciled against its governance (`P8-TSK-015`, ADR-0071): every regenerated
 * fragment is its enum's own — the admitted kinds (all but {@code REPUDIATE_BATCH}, `V009`'s),
 * the whole status machine, the admitted reason codes and the (kind, reason) pairing — the
 * derived four-eyes flag and the one-to-one ledger binding are written as designed, the
 * machine's trigger replaces `V006`'s blanket freeze with a narrowed grant, and the break's
 * type moves only while investigable.
 */
@DisplayName("reconciliation V007 reconciliation (P8-TSK-015)")
class ReconciliationV007MigrationTest {

    private static final String V007 =
            "db/migration/reconciliation/V007__resolution_person_kinds.sql";

    @Test
    @DisplayName("the kind, status and reason lists and the pairing are the enums' own")
    void theVocabularyIsRegeneratedFromTheEnums() {
        String sql = normalized(migration());
        assertThat(ResolutionKind.admittedByV007())
                .isEqualTo(EnumSet.complementOf(EnumSet.of(ResolutionKind.REPUDIATE_BATCH)));
        assertThat(ResolutionReasonCode.admittedByV007())
                .isEqualTo(EnumSet.complementOf(
                        EnumSet.of(ResolutionReasonCode.EVIDENCE_REPUDIATED)));
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT resolution_kind CHECK (kind IN ("
                                + ResolutionKind.sqlValueList(ResolutionKind.admittedByV007())
                                + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_status CHECK (status IN ("
                                + ResolutionStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_reason_code CHECK (reason_code IN ("
                                + ResolutionReasonCode.sqlValueList(
                                        ResolutionReasonCode.admittedByV007())
                                + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_kind_reason_pairing CHECK ("
                                + ResolutionKind.sqlReasonPairingRule(
                                        ResolutionKind.admittedByV007())
                                + ")"));
    }

    @Test
    @DisplayName("each kind admits exactly ADR-0071 section 5's reason subset")
    void eachKindAdmitsItsPinnedSubset() {
        assertThat(ResolutionKind.EVIDENCED.admittedReasonCodes())
                .containsExactlyInAnyOrder(ResolutionReasonCode.EVIDENCE_RECEIVED);
        assertThat(ResolutionKind.ACKNOWLEDGE.admittedReasonCodes())
                .containsExactlyInAnyOrder(
                        ResolutionReasonCode.TIMING_CONFIRMED,
                        ResolutionReasonCode.FEE_ACCEPTED_AS_CHARGED,
                        ResolutionReasonCode.FEE_RECOVERED,
                        ResolutionReasonCode.IMMATERIAL_DIFFERENCE,
                        ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                        ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED);
        assertThat(ResolutionKind.WRITE_OFF.admittedReasonCodes())
                .containsExactlyInAnyOrder(
                        ResolutionReasonCode.LOSS_ACCEPTED,
                        ResolutionReasonCode.IMMATERIAL_DIFFERENCE,
                        ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED,
                        ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                        ResolutionReasonCode.UNATTRIBUTABLE_AGED);
        assertThat(ResolutionKind.TRANSFER_TO_ACCOUNT.admittedReasonCodes())
                .containsExactlyInAnyOrder(
                        ResolutionReasonCode.FUNDS_ATTRIBUTED,
                        ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                        ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED);
        assertThat(ResolutionKind.OFFSET_SUSPENSE.admittedReasonCodes())
                .containsExactlyInAnyOrder(
                        ResolutionReasonCode.DUPLICATE_BY_COUNTERPARTY,
                        ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED,
                        ResolutionReasonCode.INTERNAL_PROCESSING_ERROR);
        assertThat(ResolutionKind.RECOGNISE_GAIN.admittedReasonCodes())
                .containsExactlyInAnyOrder(ResolutionReasonCode.UNATTRIBUTABLE_AGED);
        assertThat(ResolutionKind.MANUAL_MATCH.admittedReasonCodes())
                .containsExactlyInAnyOrder(ResolutionReasonCode.AMBIGUITY_RESOLVED_BY_EVIDENCE);
        assertThat(ResolutionKind.REPUDIATE_BATCH.admittedReasonCodes())
                .containsExactlyInAnyOrder(ResolutionReasonCode.EVIDENCE_REPUDIATED);
        // EVIDENCE_RECEIVED is the platform's alone: no person kind admits it.
        for (ResolutionKind kind : ResolutionKind.values()) {
            if (kind != ResolutionKind.EVIDENCED) {
                assertThat(kind.admittedReasonCodes())
                        .doesNotContain(ResolutionReasonCode.EVIDENCE_RECEIVED);
            }
        }
    }

    @Test
    @DisplayName("four-eyes is derived and the ledger binding one-to-one, for every writer")
    void theDerivedFlagAndBindingAreWritten() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT resolution_four_eyes_derived CHECK ( four_eyes ="
                                + " (kind <> 'EVIDENCED' AND NOT (kind = 'ACKNOWLEDGE' AND"
                                + " proposed_amount_minor = 0)))"))
                .contains(normalized(
                        "ADD CONSTRAINT resolution_posting_kind_names_proposal CHECK ("
                                + " (kind IN ('WRITE_OFF', 'TRANSFER_TO_ACCOUNT',"
                                + " 'RECOGNISE_GAIN')) = (adjustment_proposal_id IS NOT"
                                + " NULL))"))
                .contains("resolution_approved_posting_names_entry")
                .contains("resolution_transfer_names_target")
                .contains("resolution_manual_match_names_candidate")
                .contains("resolution_offset_names_item");
        for (ResolutionKind kind : ResolutionKind.values()) {
            assertThat(kind.postsAdjustment())
                    .isEqualTo(kind.ledgerReasonCode().isPresent());
        }
    }

    @Test
    @DisplayName("the machine's trigger and the narrowed grant replace V006's freeze; the"
            + " break's type moves only while investigable")
    void theMachineReplacesTheFreeze() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("DROP TRIGGER resolution_is_frozen ON reconciliation.resolution")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.resolution")
                .contains(normalized(
                        "IF NOT (" + ResolutionStatus.sqlTransitionRule() + ") THEN"))
                .contains("a resolution is never deleted")
                .contains(normalized(
                        "GRANT UPDATE (status, decided_by, decided_by_type, decided_at,"
                                + " status_changed_at, journal_entry_id, decision_id,"
                                + " park_id) ON reconciliation.resolution TO finapp_app"))
                .contains("BEFORE UPDATE OF type ON reconciliation.break")
                .contains(normalized(
                        "IF NEW.type NOT IN (" + BreakType.sqlSuspenseOwningList() + ")"))
                .contains(normalized(
                        "ADD CONSTRAINT expectation_event_type CHECK (event_type IN ("
                                + " 'OPENED', 'KEY_COLLISION', 'ALLOCATED', 'RESOLVED'))"));
        assertThat(sql).doesNotContain("GRANT DELETE");
    }

    // -----------------------------------------------------------------

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV007MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V007)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V007);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + V007, e);
        }
    }
}
