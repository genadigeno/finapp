package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close's statements, pinned (`P14-TSK-012`; {@code CloseRunStatementsTest}'s shape): the arbiters are these and {@code accounting V008} beneath them, proven against PostgreSQL by {@code PeriodCloseDatabaseTest} and {@code
 * PeriodReopenDatabaseTest} (owed, C5). A pin proves only the text; it keeps the conditions from leaving unnoticed.
 */
@DisplayName("the close's statements (P14-TSK-012)")
class PeriodCloseStatementsTest {

    @Test
    @DisplayName("every edge is conditional on its state and revision, the proposal's on no unlock pending too; only the reopen moves the revision, and it releases the lock and the pin")
    void theEdgesAreConditional() {
        assertThat(JdbcAccountingPeriodStore.MARK_CLOSE_PROPOSED_SQL).contains("SET status = 'CLOSE_PROPOSED'").contains("WHERE id = ? AND status = 'LOCKED' AND revision = ? AND unlock_proposed_by IS NULL");
        assertThat(JdbcAccountingPeriodStore.MARK_CLOSED_SQL).contains("SET status = 'CLOSED'").contains("WHERE id = ? AND status = 'CLOSE_PROPOSED' AND revision = ?").doesNotContain("locked_at").doesNotContain("revision =  ");
        assertThat(JdbcAccountingPeriodStore.MARK_CLOSE_REJECTED_SQL).contains("SET status = 'LOCKED'").contains("WHERE id = ? AND status = 'CLOSE_PROPOSED' AND revision = ?").doesNotContain("locked_at");
        assertThat(JdbcAccountingPeriodStore.MARK_REOPENED_SQL).contains("SET status = 'OPEN', revision = revision + 1").contains("locked_by = NULL, locked_by_type = NULL, locked_at = NULL, pinned_mapping_version_id = NULL")
                .contains("WHERE id = ? AND status = 'CLOSED' AND revision = ?");
    }

    @Test
    @DisplayName("a decision is conditional on PROPOSED; a proposal and a reopen are taken FOR UPDATE of their own rows; the latest proposal by the database's stamp")
    void theDecisionsAreConditional() {
        assertThat(JdbcPeriodCloseStore.DECIDE_SQL).endsWith("WHERE id = ? AND status = 'PROPOSED'");
        assertThat(JdbcPeriodCloseStore.APPROVE_REOPEN_SQL).endsWith("WHERE id = ? AND status = 'PROPOSED'");
        assertThat(JdbcPeriodCloseStore.LOCK_CLOSE_SQL).endsWith("WHERE c.id = ? FOR UPDATE OF c");
        assertThat(JdbcPeriodCloseStore.LOCK_PENDING_REOPEN_SQL).as("P14-TSK-024: the pending one, which the partial unique keeps single").endsWith("WHERE period_id = ? AND status = 'PROPOSED' FOR UPDATE");
        assertThat(JdbcPeriodCloseStore.LATEST_CLOSE_SQL).endsWith("WHERE c.period_id = ? ORDER BY c.proposed_at DESC, c.id DESC LIMIT 1");
    }

    @Test
    @DisplayName("the independent review, M-5: the proposed sets are the statement's to select, newest first, never a Java filter over the first of a period's sets")
    void theProposedSetsAreSelected() {
        assertThat(JdbcValuationRateSetStore.PROPOSED_OF_SQL).endsWith("WHERE s.period_id = ? AND s.status = 'PROPOSED' ORDER BY s.id DESC LIMIT ?");
    }

    @Test
    @DisplayName("the attempt evidence and when it concluded are the frozen attempt's own, joined by the id the close record names (P14-TSK-024: concluded_at as stored, never another stamp)")
    void theAttemptsEvidenceIsItsOwn() {
        assertThat(JdbcPeriodCloseStore.FROZEN_ATTEMPTS_SQL).startsWith("SELECT f.check_code, a.evidence, a.concluded_at FROM accounting.period_close_check f")
                .contains("JOIN accounting.close_check a ON a.id = f.close_check_id").endsWith("WHERE f.close_id = ?");
    }

    @Test
    @DisplayName("P14-TSK-024: an end is conditional on PROPOSED, as the approval is; the unlock's end copies the request from the row the act holds and clears only that proposal; the lookups name the arbiter that keeps each single")
    void theEndsAreConditional() {
        assertThat(JdbcPeriodCloseStore.END_REOPEN_SQL).startsWith("UPDATE accounting.period_reopen SET status = ?, ended_by = ?, ended_by_type = ?, ended_at = clock_timestamp(), end_reason = ?")
                .endsWith("WHERE id = ? AND status = 'PROPOSED'");
        assertThat(JdbcPeriodCloseStore.PENDING_REOPEN_SQL).endsWith("WHERE period_id = ? AND status = 'PROPOSED'");
        assertThat(JdbcPeriodCloseStore.APPROVED_REOPEN_AT_SQL).endsWith("WHERE period_id = ? AND period_revision = ? AND status = 'APPROVED'");
        // P14-DOC-001 (P14-TSK-024's re-review (a)): the reopen named, by its id - never a revision's latest end.
        assertThat(JdbcPeriodCloseStore.ENDED_REOPEN_SQL).endsWith("WHERE period_id = ? AND id = ? AND status IN ('REJECTED', 'WITHDRAWN')")
                .doesNotContain("ORDER BY").doesNotContain("LIMIT");
        assertThat(JdbcAccountingPeriodStore.INSERT_UNLOCK_END_SQL)
                .contains("SELECT ?, p.id, p.revision, p.locked_at, p.unlock_proposed_by, p.unlock_proposed_by_type, p.unlock_proposed_at, ?, ?, ?, clock_timestamp(), ?, ?")
                .endsWith("WHERE p.id = ? AND p.status = 'LOCKED' AND p.revision = ? AND p.unlock_proposed_by = ? AND p.unlock_approved_by IS NULL");
        assertThat(JdbcAccountingPeriodStore.CLEAR_UNLOCK_SQL).startsWith("UPDATE accounting.accounting_period SET unlock_proposed_by = NULL, unlock_proposed_by_type = NULL, unlock_proposed_at = NULL, unlock_proposal_reason = NULL WHERE")
                .endsWith("WHERE id = ? AND status = 'LOCKED' AND revision = ? AND unlock_proposed_by = ? AND unlock_approved_by IS NULL").doesNotContain("locked_").doesNotContain("pinned_");
        // P14-DOC-001: the unlock named, by period_unlock_end_once's key - the lock instance the period's own row's, never a lock's latest end.
        assertThat(JdbcAccountingPeriodStore.UNLOCK_END_SQL).contains("JOIN accounting.accounting_period p ON p.id = u.period_id AND p.locked_at = u.period_locked_at")
                .endsWith("WHERE u.period_id = ? AND u.proposed_at = ?").doesNotContain("ORDER BY").doesNotContain("LIMIT");
    }

    @Test
    @DisplayName("a break's birth takes its generation into the key's conflict; what a finding is compared with is each subject's latest generation")
    void theGenerationsStatements() {
        assertThat(JdbcAccountingBreakStore.INSERT_SQL).contains("subject_ref, generation, evidence").endsWith("ON CONFLICT ON CONSTRAINT accounting_break_born_once DO NOTHING");
        assertThat(JdbcAccountingBreakStore.HELD_SQL).startsWith("SELECT DISTINCT ON (subject_ref) subject_ref, status, evidence, generation").endsWith("ORDER BY subject_ref, generation DESC");
    }
}
