package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;

/**
 * What a period's return to {@code OPEN} does to its FINAL report runs (`P14-TSK-008`; ADR-0115 decision 3;
 * {@code INV-ACC-04}): supersedes every one of them, in the caller's transaction, before the period moves - so no FINAL
 * run ever stands over a period whose posting-date range can change again. The unlock calls it (`P14-TSK-005`'s
 * {@link AccountingPeriods#approveUnlock}); the reopen will (`P14-TSK-012`). {@code accounting V004}'s
 * {@code accounting_period_reopens_with_its_runs_superseded} refuses the period's edge for every writer that did not.
 * {@link ReportRuns} is the implementation.
 */
@FunctionalInterface
public interface ReportRunSupersession {

    /**
     * Every {@code FINAL} run of {@code period} -> {@code SUPERSEDED} on {@code unitOfWork}, the caller holding the period
     * row {@code FOR UPDATE}.
     *
     * @return how many were superseded
     */
    int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, Actor actor, CorrelationId correlation);
}
