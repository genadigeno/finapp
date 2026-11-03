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
        assertThat(JdbcPeriodCloseStore.LOCK_REOPEN_AT_SQL).endsWith("WHERE period_id = ? AND period_revision = ? FOR UPDATE");
        assertThat(JdbcPeriodCloseStore.LATEST_CLOSE_SQL).endsWith("WHERE c.period_id = ? ORDER BY c.proposed_at DESC, c.id DESC LIMIT 1");
    }

    @Test
    @DisplayName("the independent review, M-5: the proposed sets are the statement's to select, newest first, never a Java filter over the first of a period's sets")
    void theProposedSetsAreSelected() {
        assertThat(JdbcValuationRateSetStore.PROPOSED_OF_SQL).endsWith("WHERE s.period_id = ? AND s.status = 'PROPOSED' ORDER BY s.id DESC LIMIT ?");
    }

    @Test
    @DisplayName("the attempt evidence is the frozen attempt's own, joined by the id the close record names")
    void theAttemptsEvidenceIsItsOwn() {
        assertThat(JdbcPeriodCloseStore.ATTEMPT_EVIDENCE_SQL).contains("JOIN accounting.close_check a ON a.id = f.close_check_id").endsWith("WHERE f.close_id = ?");
    }

    @Test
    @DisplayName("a break's birth takes its generation into the key's conflict; what a finding is compared with is each subject's latest generation")
    void theGenerationsStatements() {
        assertThat(JdbcAccountingBreakStore.INSERT_SQL).contains("subject_ref, generation, evidence").endsWith("ON CONFLICT ON CONSTRAINT accounting_break_born_once DO NOTHING");
        assertThat(JdbcAccountingBreakStore.HELD_SQL).startsWith("SELECT DISTINCT ON (subject_ref) subject_ref, status, evidence, generation").endsWith("ORDER BY subject_ref, generation DESC");
    }
}
