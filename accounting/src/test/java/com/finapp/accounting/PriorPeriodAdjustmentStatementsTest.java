package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The prior-period adjustment's statements, pinned (`P14-TSK-013`; {@code PeriodCloseStatementsTest}'s shape): the arbiters are these and {@code accounting V009} beneath them, proven against PostgreSQL by {@code PriorPeriodAdjustmentDatabaseTest} (owed, C5). A pin proves only the
 * text; it keeps the conditions from leaving unnoticed.
 */
@DisplayName("the prior-period adjustment's statements (P14-TSK-013)")
class PriorPeriodAdjustmentStatementsTest {

    @Test
    @DisplayName("each edge is conditional on PROPOSED; a POST names its entry, a REJECT does not; the database stamps")
    void theEdgesAreConditional() {
        assertThat(JdbcPriorPeriodAdjustmentStore.POST_SQL).contains("SET status = 'POSTED', journal_entry_id = ?").contains("decided_at = clock_timestamp()").endsWith("WHERE id = ? AND status = 'PROPOSED'");
        assertThat(JdbcPriorPeriodAdjustmentStore.REJECT_SQL).contains("SET status = 'REJECTED'").contains("decided_at = clock_timestamp()").doesNotContain("journal_entry_id").endsWith("WHERE id = ? AND status = 'PROPOSED'");
        assertThat(JdbcPriorPeriodAdjustmentStore.INSERT_SQL).contains("'PROPOSED'").contains("clock_timestamp()").doesNotContain("journal_entry_id");
    }

    @Test
    @DisplayName("the month's live adjustments are locked in one statement, in id order, the period rows left alone; one row's lock is its own; the filter is the database's, before the LIMIT")
    void theLocks() {
        assertThat(JdbcPriorPeriodAdjustmentStore.LOCK_CORRECTIONS_SQL).endsWith("WHERE a.corrected_period_id = ? AND a.status <> 'REJECTED' ORDER BY a.id FOR UPDATE OF a");
        assertThat(JdbcPriorPeriodAdjustmentStore.LOCK_SQL).endsWith("WHERE a.id = ? FOR UPDATE OF a");
        assertThat(JdbcPriorPeriodAdjustmentStore.OF_CORRECTED_SQL).endsWith("WHERE a.corrected_period_id = ? ORDER BY a.id").doesNotContain("FOR ");
        assertThat(JdbcPriorPeriodAdjustmentStore.PENDING_SQL).endsWith("WHERE a.status = 'PROPOSED' ORDER BY a.proposed_at, a.id LIMIT ?");
    }

    @Test
    @DisplayName("every column a row reads and an insert writes exists in V009, under its own name")
    void theColumnsAreV009s() {
        String migration = PriorPeriodAdjustmentMigrationTest.migration();
        for (String column : new String[] {"corrected_period_id", "corrected_revision", "target_period_id", "target_revision", "posting_date", "adjustment_proposal_id", "journal_entry_id", "status", "proposed_by", "proposed_by_type", "proposed_at",
                "proposal_reason", "decided_by", "decided_by_type", "decided_at", "decision_reason", "correlation_id"}) {
            assertThat(migration).contains("    " + column + " ");
            assertThat(JdbcPriorPeriodAdjustmentStore.FIND_SQL + JdbcPriorPeriodAdjustmentStore.INSERT_SQL + JdbcPriorPeriodAdjustmentStore.POST_SQL).contains(column);
        }
    }
}
