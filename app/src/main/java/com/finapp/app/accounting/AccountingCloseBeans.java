package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.JdbcCloseRunStore;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the close run (`P14-TSK-011`) - its own configuration beside {@link AccountingBeans} and {@link
 * AccountingBreakBeans}, so the module's parallel tasks each add theirs without editing one file: the store and the
 * machine, the judges and what they read through, the doors, the meters, and the leaderless schedule with its eager
 * {@code finapp.accounting.sweeper.enabled} gauge.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingCloseBeans {

    @Bean
    CloseRunStore closeRunStore() {
        return new JdbcCloseRunStore();
    }

    /**
     * The check leg's lease on the database clock (2 minutes), its renewal (every 30 seconds), the run's bound (2
     * hours), the reclaims (3) and a deferral's wait (30 seconds) - refused at start-up unless a lease holds three
     * renewals and the bound a lease.
     */
    @Bean
    CloseRuns.Leases closeRunLeases(
            @Value("${finapp.accounting.close.lease:PT2M}") Duration lease,
            @Value("${finapp.accounting.close.renew-every:PT30S}") Duration renewEvery,
            @Value("${finapp.accounting.close.max-run:PT2H}") Duration maxRun,
            @Value("${finapp.accounting.close.max-reclaims:3}") int maxReclaims,
            @Value("${finapp.accounting.close.deferral:PT30S}") Duration deferral) {
        return new CloseRuns.Leases(lease, renewEvery, maxRun, maxReclaims, deferral);
    }

    @Bean
    CloseRuns closeRuns(
            CloseRunStore closeRunStore,
            AccountingPeriodStore accountingPeriodStore,
            AccountingBreakStore accountingBreakStore,
            CloseRuns.Leases closeRunLeases,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new CloseRuns(closeRunStore, accountingPeriodStore, accountingBreakStore, closeRunLeases, auditWriter,
                idGenerator, clock);
    }

    /** accounting's port over reconciliation's own reads and the journal - {@code SELECT} only, on the check's snapshot. */
    @Bean
    ReconciliationDisclosure reconciliationDisclosure(ReportReadings reconciliationReportReadings) {
        return new ProductReconciliationDisclosure(reconciliationReportReadings, new LedgerFlooredPostings());
    }

    /** The FINAL trial balance, as the close reads it. */
    @Bean
    FinalTrialBalance finalTrialBalance(ReportRuns reportRuns, ReportRunGenerator reportRunGenerator,
            AccountingTransactions accountingTransactions, ReportSnapshots reportSnapshots) {
        return new FinalTrialBalanceReader(reportRuns, reportRunGenerator, accountingTransactions,
                FinalTrialBalanceReader.overSnapshots(reportSnapshots));
    }

    /**
     * The judges. Each read on its own verified {@code REPEATABLE READ READ ONLY} snapshot ({@link GlSnapshots},
     * private to this bean), every statement bounded by {@code finapp.accounting.checks.statement-timeout} - the three
     * raising checks' own bound.
     */
    @Bean
    CloseChecks closeChecks(
            AccountingChecks accountingChecks,
            FinalTrialBalance finalTrialBalance,
            AccrualCompleteness accrualCompleteness,
            SourceProofs sourceProofs,
            ReconciliationDisclosure reconciliationDisclosure,
            ReconciliationRunCoverage reconciliationRunCoverage,
            ValuationRateSets valuationRateSets,
            AccountingPeriodStore accountingPeriodStore,
            DataSource dataSource,
            @Value("${finapp.accounting.checks.statement-timeout:" + AccountingBreakBeans.CHECKS_STATEMENT_TIMEOUT + "}")
                    Duration statementTimeout) {
        GlSnapshots snapshots = new GlSnapshots(dataSource::getConnection, statementTimeout);
        return new CloseChecks(accountingChecks::run, finalTrialBalance, accrualCompleteness, sourceProofs,
                reconciliationDisclosure, reconciliationRunCoverage, valuationRateSets, accountingPeriodStore, snapshots::read);
    }

    @Bean
    CloseRunDesk closeRunDesk(
            CloseRuns closeRuns,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new CloseRunDesk(closeRuns, idempotentExecutor, accountingTransactions, accountingFlowScope);
    }

    @Bean
    CloseRunMetrics closeRunMetrics(MeterRegistry meterRegistry) {
        return new CloseRunMetrics(meterRegistry);
    }

    /** The close schedule - every instance, no leader, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.accounting.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    AccountingCloseSchedule accountingCloseSchedule(
            AccountingPeriods accountingPeriods,
            CloseRuns closeRuns,
            CloseChecks closeChecks,
            ReportRunGenerator reportRunGenerator,
            RegulatorySubmissionDriver regulatorySubmissionDriver,
            AccountingTransactions accountingTransactions,
            CloseRunMetrics closeRunMetrics,
            AccountingFlowScope accountingFlowScope,
            IdGenerator idGenerator,
            @Value("${finapp.accounting.close.open-poll:PT1H}") Duration openPoll,
            @Value("${finapp.accounting.close.checks-poll:PT30S}") Duration checksPoll,
            @Value("${finapp.accounting.close.orphans-poll:PT1M}") Duration orphansPoll,
            @Value("${finapp.accounting.close.inquiry-poll:PT1M}") Duration inquiryPoll,
            @Value("${finapp.accounting.close.checks-batch:18}") int checksBatch,
            @Value("${finapp.accounting.close.orphans-batch:10}") int orphansBatch) {
        return new AccountingCloseSchedule(accountingPeriods, closeRuns, closeChecks, reportRunGenerator::completeOrphaned,
                regulatorySubmissionDriver::inquireDue, accountingTransactions, closeRunMetrics, accountingFlowScope, idGenerator,
                new AccountingCloseSchedule.Cadence(openPoll, checksPoll, orphansPoll, inquiryPoll, checksBatch, orphansBatch));
    }

    /** Whether this instance runs the close schedule - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge accountingSweeperEnabled(
            @Value("${finapp.accounting.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.accounting.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the accounting close schedule")
                .register(meterRegistry);
    }
}
