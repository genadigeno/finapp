package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close run store's arbiters, pinned (`P14-TSK-011`; {@code PHASE_14_PLAN.md} section 8.3 rows 5-6; {@code INV-ACC-09}): the statements that
 * make "one attempt, one holder, one conclusion" true against PostgreSQL - the claim {@code FOR UPDATE SKIP LOCKED} under a lease on the
 * database's clock, the renewal and the conclusion fenced by {@code holder}. The hermetic half; the database half is
 * {@code CloseRunDatabaseTest#tenWorkersOneAttempt}, {@code #aDeadWorkersCheckIsReclaimed} and {@code #aLiveWorkerOutlastsItsLease} (owed, C5).
 */
@DisplayName("the close run store's conditional statements (P14-TSK-011)")
class CloseRunStatementsTest {

    @Test
    @DisplayName("the claim takes one row FOR UPDATE SKIP LOCKED where the lease has passed on the database's clock - ten workers, disjoint"
            + " rows, none waiting - in one statement, so no row is held across a judgement")
    void theClaimIsSkipLockedUnderALease() {
        assertThat(JdbcCloseRunStore.CLAIM_SQL)
                .contains("LIMIT 1 FOR UPDATE OF candidate SKIP LOCKED)")
                .contains("candidate.status = 'PENDING' AND candidate.lease_until <= clock_timestamp()")
                .contains("lease_until = clock_timestamp() + ? * interval '1 millisecond'")
                .contains("claimed_at = clock_timestamp()")
                .startsWith("UPDATE accounting.close_check attempt")
                .contains("RETURNING attempt.id");
    }

    @Test
    @DisplayName("a lapsed holder replaced counts one reclaim; an empty holder none - a deferral is no death")
    void aReclaimIsCountedOnlyOverALapsedHolder() {
        assertThat(JdbcCloseRunStore.CLAIM_SQL)
                .contains("reclaims = attempt.reclaims + CASE WHEN attempt.holder IS NULL THEN 0 ELSE 1 END");
        assertThat(JdbcCloseRunStore.DEFER_SQL).isEqualTo("UPDATE accounting.close_check SET holder = NULL, lease_until ="
                + " clock_timestamp() + ? * interval '1 millisecond' WHERE id = ? AND status = 'PENDING' AND holder = ?");
    }

    @Test
    @DisplayName("the claim reads the period's present lock - a run an unlock left behind is never claimed")
    void anOldLocksAttemptsAreNobodys() {
        assertThat(JdbcCloseRunStore.CLAIM_SQL)
                .contains("period.status = 'LOCKED' AND period.locked_at = run.period_locked_at");
    }

    @Test
    @DisplayName("the breaks' check is not claimable while a raising check of its run is PENDING - no stale pass")
    void theBreaksWaitForTheRaisingChecks() {
        assertThat(JdbcCloseRunStore.CLAIM_SQL)
                .contains("candidate.check_code <> 'OPEN_ACCOUNTING_BREAKS' OR NOT EXISTS (SELECT 1")
                .contains("raising.status = 'PENDING'")
                .contains("raising.check_code IN ('GL_EQUALS_LEDGER', 'RULE_CONFORMANCE', 'SOURCE_COMPLETENESS')");
        assertThat(BreakFinding.CHECKS).as("the statement's list is the raising checks'").containsExactlyInAnyOrder(
                CloseCheckCode.GL_EQUALS_LEDGER, CloseCheckCode.RULE_CONFORMANCE, CloseCheckCode.SOURCE_COMPLETENESS);
    }

    @Test
    @DisplayName("the conclusion is fenced: only from PENDING, only by the holder - an overtaken worker's moves nothing")
    void theConclusionIsFenced() {
        assertThat(JdbcCloseRunStore.CONCLUDE_SQL).isEqualTo("UPDATE accounting.close_check SET status = ?, judged_by = ?,"
                + " failures = ?, evidence = ?, concluded_at = clock_timestamp() WHERE id = ? AND status = 'PENDING'"
                + " AND holder = ?");
    }

    @Test
    @DisplayName("the renewal is fenced and bounded: only the holder, only within the run's bound of its claim, on the database's clock")
    void theRenewalIsFencedAndBounded() {
        assertThat(JdbcCloseRunStore.RENEW_SQL).isEqualTo("UPDATE accounting.close_check SET lease_until = clock_timestamp()"
                + " + ? * interval '1 millisecond' WHERE id = ? AND status = 'PENDING' AND holder = ?"
                + " AND claimed_at + ? * interval '1 millisecond' > clock_timestamp()");
    }

    @Test
    @DisplayName("the birth is insert-if-absent on the lock instance; the checks' first conclusion stamped once, only with none pending")
    void theBirthAndTheStamp() {
        assertThat(JdbcCloseRunStore.INSERT_RUN_SQL).endsWith("ON CONFLICT ON CONSTRAINT close_run_one_per_lock DO NOTHING");
        assertThat(JdbcCloseRunStore.LOCKED_WITHOUT_RUN_SQL).contains("period.status = 'LOCKED' AND NOT EXISTS")
                .contains("run.period_locked_at = period.locked_at");
        assertThat(JdbcCloseRunStore.MARK_CONCLUDED_SQL).contains("run.checks_concluded_at IS NULL AND NOT EXISTS")
                .contains("attempt.status = 'PENDING'");
    }

    @Test
    @DisplayName("a reader's history is bounded per check, newest first")
    void theHistoryIsBounded() {
        assertThat(JdbcCloseRunStore.ATTEMPTS_SQL).contains("row_number() OVER (PARTITION BY c.check_code ORDER BY c.attempt DESC)")
                .contains("attempt.recency <= ?").contains("ORDER BY attempt.check_code, attempt.attempt DESC");
    }
}
