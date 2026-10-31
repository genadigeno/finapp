package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The report run store's arbiters, pinned (`P14-TSK-008`; {@code PHASE_14_PLAN.md} section 8.3 row 7, 8.4 (c);
 * {@code INV-ACC-04}): the statements that make "one run, one completion, one holder" true against PostgreSQL. Each is
 * conditional on exactly the state its edge leaves from - the completion and the failure on {@code GENERATING} <em>and
 * the attempt the caller holds</em>, so a reclaimed generator's late completion moves nothing; the reclaim on the attempt
 * read <em>and</em> an expired lease on the database's clock, so two reclaimers never both win and a live generator is
 * never overtaken; the supersession on {@code FINAL}. The hermetic half; the database half is {@code
 * ReportRunDatabaseTest#twoGeneratorsOneRun} and {@code #aDeadGeneratorIsReclaimed} (owed, C5).
 */
@DisplayName("the report run store's conditional statements (P14-TSK-008)")
class ReportRunStatementsTest {

    @Test
    @DisplayName("the completion carries status, content and hash in one statement, only from GENERATING at the attempt")
    void theCompletionIsConditional() {
        assertThat(JdbcReportRunStore.COMPLETE_SQL).isEqualTo("UPDATE accounting.report_run SET status = ?, content = ?,"
                + " content_sha256 = ?, completed_at = clock_timestamp() WHERE id = ? AND status = 'GENERATING'"
                + " AND attempt = ?");
    }

    @Test
    @DisplayName("the reclaim is one attempt more, only at the attempt read and past the lease, the new lease on the"
            + " database's clock")
    void theReclaimIsConditional() {
        assertThat(JdbcReportRunStore.RECLAIM_SQL).isEqualTo("UPDATE accounting.report_run SET attempt = attempt + 1,"
                + " lease_until = clock_timestamp() + ? * interval '1 millisecond' WHERE id = ? AND status = 'GENERATING'"
                + " AND attempt = ? AND lease_until <= clock_timestamp()");
    }

    @Test
    @DisplayName("the failure moves only from GENERATING at the attempt; the supersession only FINAL runs of the period")
    void theFailureAndTheSupersessionAreConditional() {
        assertThat(JdbcReportRunStore.FAIL_SQL).isEqualTo("UPDATE accounting.report_run SET status = 'FAILED',"
                + " failure_code = ?, failed_at = clock_timestamp() WHERE id = ? AND status = 'GENERATING'"
                + " AND attempt = ?");
        assertThat(JdbcReportRunStore.SUPERSEDE_SQL).isEqualTo("UPDATE accounting.report_run SET status = 'SUPERSEDED',"
                + " superseded_at = clock_timestamp(), superseded_correlation_id = ? WHERE period_id = ?"
                + " AND status = 'FINAL'");
    }

    @Test
    @DisplayName("the birth is insert-or-read on the plan's key: born GENERATING at attempt 1, leased on the database's"
            + " clock, a held key doing nothing")
    void theBirthIsInsertOrRead() {
        assertThat(JdbcReportRunStore.INSERT_SQL)
                .contains("'GENERATING', 1, clock_timestamp() + ? * interval '1 millisecond', ?)")
                .endsWith(" ON CONFLICT ON CONSTRAINT report_run_key DO NOTHING");
    }
}
