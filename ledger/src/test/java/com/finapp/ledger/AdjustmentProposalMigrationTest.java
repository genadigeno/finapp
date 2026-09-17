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

    private static String migration() {
        try (InputStream migration =
                AdjustmentProposalMigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
