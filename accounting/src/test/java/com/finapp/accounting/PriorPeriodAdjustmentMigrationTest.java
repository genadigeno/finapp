package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code accounting V009}, pinned against its definitions (`P14-TSK-013`; {@code PeriodCloseMigrationTest}'s shape; {@code INV-ACC-10}, {@code INV-AUD-04}). A pin proves only the text: the migration first runs at the phase's database pass, so {@code
 * PriorPeriodAdjustmentDatabaseTest} proves what it does (owed, C5).
 */
@DisplayName("accounting V009 (P14-TSK-013)")
class PriorPeriodAdjustmentMigrationTest {

    static String migration() {
        try (InputStream migration = PriorPeriodAdjustmentMigrationTest.class.getClassLoader().getResourceAsStream("db/migration/accounting/V009__prior_period_adjustments.sql")) {
            if (migration == null) {
                throw new IllegalStateException("V009 is not on the test classpath");
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String edges() {
        String text = migration();
        return text.substring(text.indexOf("CREATE OR REPLACE FUNCTION accounting.prior_period_adjustment_permits_only_machine_edges"), text.indexOf("CREATE TRIGGER prior_period_adjustment_permits_only_machine_edges"));
    }

    @Test
    @DisplayName("the status CHECK is PriorPeriodAdjustmentStatus's own")
    void theStatusListIsTheEnums() {
        String statuses = Arrays.stream(PriorPeriodAdjustmentStatus.values()).map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));
        assertThat(migration()).contains("CHECK (status IN (" + statuses + "))");
    }

    @Test
    @DisplayName("four eyes for every writer: whoever posted it did not propose it; persons only; a decision whole, an entry exactly when POSTED")
    void fourEyesForEveryWriter() {
        assertThat(migration())
                .contains("CONSTRAINT prior_period_adjustment_four_eyes CHECK (status <> 'POSTED' OR decided_by <> proposed_by)")
                .contains("proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')")
                .contains("(status = 'PROPOSED') = (decided_by IS NULL)")
                .contains("(decided_by IS NULL) = (decision_reason IS NULL)")
                .contains("CHECK ((status = 'POSTED') = (journal_entry_id IS NOT NULL))");
    }

    @Test
    @DisplayName("one proposal and one entry, each named once; no foreign key or name of another schema; no money")
    void oneProposalOneEntryNoOtherSchema() {
        assertThat(migration())
                .contains("CONSTRAINT prior_period_adjustment_one_proposal UNIQUE (adjustment_proposal_id)")
                .contains("CONSTRAINT prior_period_adjustment_one_entry UNIQUE (journal_entry_id)")
                .contains("REFERENCES accounting.accounting_period (id)")
                .doesNotContain("REFERENCES ledger")
                .doesNotContain("ledger.adjustment_proposal.")
                .doesNotContain("amount_minor");
    }

    @Test
    @DisplayName("the trigger: frozen but for its one decision; two edges; never deleted, never truncated; a rejection reads no period, so it is always open")
    void theMachine() {
        assertThat(edges())
                .contains("NEW.posting_date IS DISTINCT FROM OLD.posting_date")
                .contains("NEW.corrected_revision IS DISTINCT FROM OLD.corrected_revision")
                .contains("NEW.target_revision IS DISTINCT FROM OLD.target_revision")
                .contains("NEW.adjustment_proposal_id IS DISTINCT FROM OLD.adjustment_proposal_id")
                .contains("NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason")
                .contains("OLD.status = 'PROPOSED' AND NEW.status IN ('POSTED', 'REJECTED')")
                .contains("is never deleted");
        String rejection = edges().substring(edges().indexOf("IF NEW.status = 'REJECTED' THEN"), edges().indexOf("-- A birth and a POST"));
        assertThat(rejection).contains("RETURN NEW").doesNotContain("accounting_period");
        assertThat(migration()).contains("BEFORE TRUNCATE ON accounting.prior_period_adjustment").contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.prior_period_adjustment");
    }

    @Test
    @DisplayName("a birth and a POST read the corrected month, then the target, each FOR SHARE, never a neighbour unlocked: the corrected CLOSED at its revision, the target OPEN, the date inside it and after the corrected month; a future date refused at a birth")
    void theDates() {
        String body = edges().substring(edges().indexOf("-- A birth and a POST"));
        assertThat(body.indexOf("WHERE p.id = NEW.corrected_period_id FOR SHARE")).isPositive().isLessThan(body.indexOf("WHERE p.id = NEW.target_period_id FOR SHARE"));
        assertThat(body).contains("held_corrected_status <> 'CLOSED'").contains("held_corrected_revision <> NEW.corrected_revision").contains("held_target_status <> 'OPEN'")
                .contains("held_target_revision <> NEW.target_revision")
                .contains("held_target_start <= held_corrected_end").contains("NEW.posting_date < held_target_start").contains("NEW.posting_date > held_target_end")
                .contains("NEW.posting_date > (now() AT TIME ZONE 'UTC')::date + 1");
        assertThat(edges().split("FROM accounting.accounting_period p").length - 1).as("every period read in the trigger is one of the two, each FOR SHARE").isEqualTo(2);
        assertThat(body.substring(body.indexOf("IF TG_OP = 'INSERT' THEN"), body.indexOf("NEW.proposed_at := clock_timestamp()"))).as("never born into a reopened month").contains("NEW.target_revision <> 1");
        assertThat(migration()).contains("target_revision        integer     NOT NULL").contains("CHECK (target_revision >= 1)");
    }

    @Test
    @DisplayName("the grants: the decision's columns only, no DELETE")
    void theGrants() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON accounting.prior_period_adjustment TO finapp_app")
                .contains("GRANT UPDATE (status, journal_entry_id, decided_by, decided_by_type, decided_at, decision_reason) ON accounting.prior_period_adjustment TO finapp_app")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT ALL");
    }
}
