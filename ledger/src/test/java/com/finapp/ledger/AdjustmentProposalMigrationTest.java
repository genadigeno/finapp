package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proposal schema and its one-definition sources agree (`P3-TSK-021` — the
 * {@code HoldMigrationTest} discipline): the enum's value list, the {@code MoneyColumns}
 * monetary shape, the reason bound shared with {@code AuditRecord}, and the layers carrying
 * {@code INV-AUD-04}'s representable halves at the schema.
 */
@DisplayName("the adjustment proposal schema and its definitions agree (P3-TSK-021)")
class AdjustmentProposalMigrationTest {

    private static final String MIGRATION =
            "db/migration/ledger/V010__create_adjustment_proposal.sql";

    /** `P8-TSK-006`'s widening: reason codes, origins and the reconciled-position binding. */
    private static final String MIGRATION_V015 =
            "db/migration/ledger/V015__adjustment_reason_codes_and_origins.sql";

    @Test
    @DisplayName("the status CHECK lists exactly the values the enum declares")
    void theStatusListMatchesItsEnum() {
        assertThat(migration())
                .contains(
                        "CHECK (status IN (" + AdjustmentProposalStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("approver <> initiator is a CHECK - INV-AUD-04's Enforce clause, verbatim")
    void theFourEyesCheckIsTheInvariants() {
        assertThat(migration())
                .contains("CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)");
    }

    @Test
    @DisplayName("the decision columns and the status are one fact for every writer")
    void theCoherenceChecksHoldTheDecisionTogether() {
        assertThat(migration())
                .contains("CHECK ((status = 'PROPOSED') = (decided_by IS NULL))")
                .contains("CHECK ((status = 'PROPOSED') = (decided_at IS NULL))")
                .contains("CHECK ((status = 'APPROVED') = (journal_entry_id IS NOT NULL))");
    }

    @Test
    @DisplayName("the reason and reference bounds are the journal's own (one number each)")
    void theBoundsAreTheJournals() {
        assertThat(migration())
                .as("the reason bound is AuditRecord.MAX_REASON_LENGTH, reconciled - the"
                        + " P3-TSK-017 three-layer bound extended to the proposal")
                .contains("CHECK (length(reason) BETWEEN 1 AND "
                        + AuditRecord.MAX_REASON_LENGTH + ")")
                .contains("CHECK (length(reference) BETWEEN 1 AND 200)");
    }

    @Test
    @DisplayName("the monetary columns are MoneyColumns' generated shape, verbatim")
    void theMonetaryShapeIsGenerated() {
        assertThat(migration())
                .contains(
                        new MoneyColumns.ColumnNames("amount_minor", "currency", "scale")
                                .ddl());
    }

    @Test
    @DisplayName("the line directions and the currency binding are the journal's mechanisms")
    void theLinesAreTheJournalsShape() {
        assertThat(migration())
                .contains("CHECK (direction IN (" + Direction.sqlValueList() + "))")
                .contains("FOREIGN KEY (ledger_account_id, currency)")
                .contains("REFERENCES ledger.ledger_account (id, currency)");
    }

    @Test
    @DisplayName("the payload is frozen and the lines immutable, by trigger, for every writer")
    void theFreezeTriggersBindEveryWriter() {
        assertThat(migration())
                .contains("CREATE TRIGGER adjustment_proposal_permits_only_decision")
                .contains("BEFORE UPDATE ON ledger.adjustment_proposal")
                .contains("CREATE TRIGGER adjustment_proposal_line_is_frozen")
                .contains("BEFORE UPDATE OR DELETE ON ledger.adjustment_proposal_line");
    }

    @Test
    @DisplayName("an ADJUSTMENT entry cannot commit unapproved - the deferred trigger exists")
    void theJournalTriggerIsDeferredToCommit() {
        assertThat(migration())
                .contains("CREATE CONSTRAINT TRIGGER adjustment_entry_is_approved")
                .contains("AFTER INSERT ON ledger.journal_entry")
                .contains("DEFERRABLE INITIALLY DEFERRED");
    }

    @Test
    @DisplayName("the grants are the decision's columns and nothing more")
    void theGrantsAreTheDecisions() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON ledger.adjustment_proposal TO finapp_app")
                .contains("GRANT UPDATE (status, decided_by, decided_at, journal_entry_id)")
                .contains("GRANT SELECT, INSERT ON ledger.adjustment_proposal_line"
                        + " TO finapp_app")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT ALL");
    }

    // -----------------------------------------------------------------
    // V015 (P8-TSK-006): the reason-code regime and the reconciled-position binding.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the reason-code and origin lists, and their pairing, are the enums' own"
            + " (P8-TSK-006, INV-REV-04)")
    void theCodeAndOriginListsMatchTheirEnums() {
        assertThat(migrationV015())
                .contains("CHECK (reason_code IN (" + AdjustmentReasonCode.sqlValueList() + "))")
                .contains("CHECK (origin IN (" + AdjustmentOrigin.sqlValueList() + "))")
                .contains("CHECK (" + AdjustmentReasonCode.sqlPairingRule() + ")");
    }

    @Test
    @DisplayName("both columns arrive with the history defaults, and UNCODED is refused on"
            + " INSERT for every writer (INV-HIST-01, INV-REV-04)")
    void theHistoryDefaultsAndTheUncodedRefusalStand() {
        assertThat(migrationV015())
                .contains("ADD COLUMN reason_code text NOT NULL DEFAULT 'UNCODED'")
                .contains("ADD COLUMN origin      text NOT NULL DEFAULT 'MANUAL'")
                .contains("CREATE TRIGGER adjustment_proposal_requires_a_reason_code")
                .contains("BEFORE INSERT ON ledger.adjustment_proposal");
    }

    @Test
    @DisplayName("the binding's purpose list is AccountPurpose.closedToFreeAdjustments(),"
            + " generated (P8-TSK-006, ADR-0071) - its LATEST re-statement is V020's"
            + " (P9-TSK-009: the FX books closed to free adjustment)")
    void theBindingListIsTheEnums() {
        assertThat(migrationV015())
                .contains("CREATE TRIGGER adjustment_line_respects_reconciled_positions")
                .contains("BEFORE INSERT ON ledger.adjustment_proposal_line");
        assertThat(read(
                        "db/migration/ledger/V020__fx_spread_revenue_joins_the_chart.sql"))
                .as("the current generated list lives in the latest re-statement")
                .contains(
                        "CREATE OR REPLACE FUNCTION"
                                + " ledger.adjustment_line_respects_reconciled_positions")
                .contains("account_purpose IN ("
                        + AccountPurpose.sqlClosedToFreeAdjustmentsList() + ")");
    }

    @Test
    @DisplayName("the payload freeze is re-stated with both new columns frozen (V015)")
    void theFreezeCoversTheNewColumns() {
        assertThat(migrationV015())
                .contains("OLD.reason_code <> NEW.reason_code")
                .contains("OLD.origin <> NEW.origin")
                // Re-stated, not re-bound: V010's trigger stands and picks up the new body.
                .contains(
                        "CREATE OR REPLACE FUNCTION"
                                + " ledger.adjustment_proposal_permits_only_decision");
        assertThat(migrationV015())
                .as("no new UPDATE grant arrives with the columns: the app role still"
                        + " touches only the decision's four (V010's narrowing)")
                .doesNotContain("GRANT");
    }

    private static String migrationV015() {
        return read(MIGRATION_V015);
    }

    private static String migration() {
        return read(MIGRATION);
    }

    private static String read(String path) {
        try (InputStream migration =
                AdjustmentProposalMigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(path)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + path);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
