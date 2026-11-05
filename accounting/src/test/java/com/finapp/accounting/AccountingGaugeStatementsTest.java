package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statements behind the gauges `P14-TSK-020` adds, pinned. <strong>Text pins, and no more</strong>: they hold the words a clause-by-clause walk against the DDL was made of (`CloseRunStatementsTest`'s shape), and they prove no behaviour. What PostgreSQL answers is
 * {@code CloseRunStallDatabaseTest}, {@code AccountingBreakDatabaseTest}, {@code PriorPeriodAdjustmentDatabaseTest} and {@code AccountingPeriodDatabaseTest}, each owed (C5).
 */
@DisplayName("the gauges' statements, pinned (P14-TSK-020)")
class AccountingGaugeStatementsTest {

    private static final String HELD_BACK = " AND period.status = 'LOCKED' AND period.locked_at = run.period_locked_at"
            + " AND (candidate.check_code <> 'OPEN_ACCOUNTING_BREAKS' OR NOT EXISTS (SELECT 1"
            + " FROM accounting.close_check raising WHERE raising.close_run_id = candidate.close_run_id"
            + " AND raising.status = 'PENDING'"
            + " AND raising.check_code IN ('GL_EQUALS_LEDGER', 'RULE_CONFORMANCE', 'SOURCE_COMPLETENESS')))";

    @Test
    @DisplayName("a stalled attempt holds the claim's own words for what it holds back, so a dependency's wait never reads as a stall, and the claim still reads them")
    void aStallExcludesWhatTheClaimHoldsBack() {
        assertThat(JdbcCloseRunStore.STALLED_ATTEMPTS_SQL).contains(HELD_BACK);
        assertThat(JdbcCloseRunStore.CLAIM_SQL).contains("candidate.lease_until <= clock_timestamp()" + HELD_BACK);
    }

    @Test
    @DisplayName("a stalled attempt is past the bound on the database's clock, of a present run, with no claim of a present run live; a holder is asked by name, since NULL reads as not true")
    void aStallIsPastTheBoundWithNothingLive() {
        assertThat(JdbcCloseRunStore.STALLED_ATTEMPTS_SQL)
                .startsWith("SELECT count(*) FROM accounting.close_check candidate")
                .contains("candidate.status = 'PENDING' AND candidate.lease_until <= clock_timestamp() - ? * interval '1 millisecond'")
                .contains("NOT EXISTS (SELECT 1 FROM accounting.close_check live")
                .contains("live.status = 'PENDING' AND live.holder IS NOT NULL AND live.lease_until > clock_timestamp()")
                .contains("live_period.status = 'LOCKED' AND live_period.locked_at = live_run.period_locked_at")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    @DisplayName("an aged break is OPEN and raised past the bound on the database's clock, per type: never ACKNOWLEDGED")
    void anAgedBreakIsOpen() {
        assertThat(JdbcAccountingBreakStore.AGED_OPEN_SQL).isEqualTo("SELECT break_type, count(*) FROM accounting.accounting_break"
                + " WHERE status = 'OPEN' AND raised_at <= clock_timestamp() - ? * interval '1 millisecond' GROUP BY break_type");
    }

    @Test
    @DisplayName("a standing adjustment is PROPOSED past the bound on the database's clock: a count, and no amount in the statement")
    void aStandingAdjustmentIsProposed() {
        assertThat(JdbcPriorPeriodAdjustmentStore.STANDING_SQL).isEqualTo("SELECT count(*) FROM accounting.prior_period_adjustment"
                + " WHERE status = 'PROPOSED' AND proposed_at <= clock_timestamp() - ? * interval '1 millisecond'");
    }

    @Test
    @DisplayName("the age measures from the database's own date, UTC, whichever zone a session holds")
    void theAgeReadsTheDatabasesDate() {
        assertThat(JdbcAccountingPeriodStore.TODAY_SQL).isEqualTo("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date");
    }
}
