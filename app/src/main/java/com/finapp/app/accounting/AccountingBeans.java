package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappings;
import com.finapp.accounting.JdbcAccountingPeriodStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.LedgerChart;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for accounting - the module's first beans (`P14-TSK-002`; {@code RiskBeans}' shape): its unit of work and the
 * posting rule register's audited door over {@link PostingRuleRegister#CURRENT}. Later tasks add theirs here.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingBeans {

    /** accounting's unit of work - one transaction, default propagation. */
    @Bean
    AccountingTransactions accountingTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new SpringAccountingTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    PostingRuleDesk postingRuleDesk(
            AccountingTransactions accountingTransactions,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new PostingRuleDesk(PostingRuleRegister.CURRENT, accountingTransactions, auditWriter, idGenerator, clock);
    }

    // ------------------------------------------------------------------ the GL chart and mapping (P14-TSK-003)

    @Bean
    GlMappingStore glMappingStore() {
        return new JdbcGlMappingStore();
    }

    /** accounting's port over the ledger's posting cutoff (`P14-TSK-004`) - the ledger's own API, nothing added. */
    @Bean
    PostingCutoff postingCutoff(com.finapp.ledger.PostingCutoffs postingCutoffs) {
        return new LedgerPostingCutoff(postingCutoffs);
    }

    /**
     * accounting's port over lending's and bnpl's accrued-through dates (`P14-TSK-006`, owner decision C3: the close
     * requires accruals caught up) - the two modules' own reads, read-only, on the caller's unit of work.
     */
    @Bean
    com.finapp.accounting.AccrualCompleteness accrualCompleteness(
            com.finapp.lending.AccrualLags accrualLags, com.finapp.bnpl.BnplAccrualStore bnplAccrualStore) {
        return new ServicingAccrualCompleteness(
                accrualLags::oldestUnaccruedByCurrency, bnplAccrualStore::oldestUnaccruedByCurrency);
    }

    /** accounting's port over the ledger's chart - SELECT only, on the caller's unit of work. */
    @Bean
    LedgerChart ledgerChart() {
        return new LedgerPurposeChart();
    }

    @Bean
    GlMappingAdministration glMappingAdministration(
            GlMappingStore glMappingStore,
            LedgerChart ledgerChart,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new GlMappingAdministration(glMappingStore, ledgerChart, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    GlMappings glMappings(GlMappingStore glMappingStore, LedgerChart ledgerChart) {
        return new GlMappings(glMappingStore, ledgerChart);
    }

    /** The door leg's spans, no attribute ({@code PHASE_14_PLAN.md} section 16). */
    @Bean
    AccountingFlowScope accountingFlowScope(Spans domainSpans) {
        return new AccountingFlowScope(domainSpans);
    }

    @Bean
    GlMappingDesk glMappingDesk(
            GlMappingAdministration glMappingAdministration,
            GlMappings glMappings,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new GlMappingDesk(glMappingAdministration, glMappings, idempotentExecutor, accountingTransactions,
                accountingFlowScope);
    }

    /** {@code finapp.accounting.gl.unmapped} - fed from the database by `P14-TSK-007` (registered at zero by `-003`). */
    @Bean
    AccountingMappingMetrics accountingMappingMetrics(com.finapp.accounting.GlMappingGap glMappingGap,
            GlSnapshots glSnapshots, Clock clock, MeterRegistry meterRegistry) {
        return new AccountingMappingMetrics(glMappingGap, glSnapshots, clock, meterRegistry);
    }

    // ------------------------------------------------------------------ the periods (P14-TSK-005)

    @Bean
    AccountingPeriodStore accountingPeriodStore() {
        return new JdbcAccountingPeriodStore();
    }

    /** C9's grace before a lock, in business days - configuration (default 2, 1 to 20). */
    @Bean
    LockGrace lockGrace(
            @Value("${finapp.accounting.period.lock-grace-business-days:" + LockGrace.DEFAULT_BUSINESS_DAYS + "}")
                    int businessDays) {
        return new LockGrace(businessDays);
    }

    @Bean
    AccountingPeriods accountingPeriods(
            AccountingPeriodStore accountingPeriodStore,
            GlMappingStore glMappingStore,
            PostingCutoff postingCutoff,
            LockGrace lockGrace,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.accounting.ReportRuns reportRuns) {
        // P14-TSK-008: the unlock supersedes the period's FINAL report runs in its own transaction.
        return new AccountingPeriods(accountingPeriodStore, glMappingStore, postingCutoff, lockGrace, auditWriter,
                outboxWriter, idGenerator, clock, reportRuns);
    }

    @Bean
    AccountingPeriodDesk accountingPeriodDesk(
            AccountingPeriods accountingPeriods,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new AccountingPeriodDesk(accountingPeriods, idempotentExecutor, accountingTransactions,
                accountingFlowScope);
    }

    /** {@code finapp.accounting.period.open.age} - read from the database, cached 15 s. */
    @Bean
    AccountingPeriodMetrics accountingPeriodMetrics(
            AccountingPeriods accountingPeriods, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new AccountingPeriodMetrics(accountingPeriods, dataSource::getConnection, clock, meterRegistry);
    }

    // ------------------------------------------------------------------ the report runs (P14-TSK-008)

    @Bean
    com.finapp.accounting.ReportRunStore reportRunStore() {
        return new com.finapp.accounting.JdbcReportRunStore();
    }

    /**
     * The definitions this build holds - every {@code ReportDefinition} bean: the trial balance first (`P14-TSK-009`,
     * {@link TrialBalanceBeans}); a definition joins by being declared, with no edit here.
     */
    @Bean
    com.finapp.accounting.reporting.ReportDefinitions reportDefinitions(
            org.springframework.beans.factory.ObjectProvider<com.finapp.accounting.reporting.ReportDefinition> definitions) {
        return com.finapp.accounting.reporting.ReportDefinitions.of(definitions.orderedStream().toList());
    }

    /**
     * The generator's lease on the database clock (default 5 minutes), its attempts' bound (default 5) and the
     * generation's time bound (default 4 minutes) - refused at start-up unless it leaves a fifth of the lease
     * (`P14-TSK-008`'s review, I-1).
     */
    @Bean
    com.finapp.accounting.ReportRuns.Leases reportRunLeases(
            @Value("${finapp.accounting.report.lease:PT5M}") java.time.Duration lease,
            @Value("${finapp.accounting.report.max-attempts:5}") int maxAttempts,
            @Value("${finapp.accounting.report.generation-timeout:PT4M}") java.time.Duration generationTimeout) {
        return new com.finapp.accounting.ReportRuns.Leases(lease, maxAttempts, generationTimeout);
    }

    /**
     * The machine; a completed PROVISIONAL run answers its question while its cut is younger than
     * {@code finapp.accounting.report.provisional-freshness} (default 15 minutes, one second to a day - `P14-TSK-009`,
     * the bound `P14-TSK-008`'s review M-8 asked for).
     */
    @Bean
    com.finapp.accounting.ReportRuns reportRuns(
            com.finapp.accounting.ReportRunStore reportRunStore,
            AccountingPeriodStore accountingPeriodStore,
            GlMappingStore glMappingStore,
            com.finapp.accounting.reporting.ReportDefinitions reportDefinitions,
            com.finapp.accounting.ReportRuns.Leases reportRunLeases,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            @Value("${finapp.accounting.report.provisional-freshness:PT15M}") java.time.Duration provisionalFreshness,
            com.finapp.accounting.ValuationRateSetStore valuationRateSetStore) {
        return new com.finapp.accounting.ReportRuns(reportRunStore, accountingPeriodStore, glMappingStore,
                reportDefinitions, reportRunLeases, auditWriter, outboxWriter, idGenerator, clock,
                new com.finapp.accounting.ReportRuns.Provisional(provisionalFreshness),
                com.finapp.accounting.ReportRuns.ApprovedSets.of(valuationRateSetStore));
    }

    /**
     * Report code's reads: a separate pooled connection, one REPEATABLE READ READ ONLY transaction bounded by the
     * generation's statement timeout, rolled back.
     */
    @Bean
    ReportSnapshots reportSnapshots(DataSource dataSource, com.finapp.accounting.ReportRuns.Leases reportRunLeases) {
        return new JdbcReportSnapshots(dataSource::getConnection, reportRunLeases.generationTimeout());
    }

    @Bean
    ReportRunMetrics reportRunMetrics(
            MeterRegistry meterRegistry, com.finapp.accounting.reporting.ReportDefinitions reportDefinitions) {
        return new ReportRunMetrics(meterRegistry, reportDefinitions);
    }

    /** T-report's legs, and the orphan leg `P14-TSK-011` schedules in AccountingCloseSchedule. */
    @Bean
    ReportRunGenerator reportRunGenerator(
            com.finapp.accounting.ReportRuns reportRuns,
            ReportSnapshots reportSnapshots,
            AccountingTransactions accountingTransactions,
            ReportRunMetrics reportRunMetrics,
            com.finapp.accounting.ReportRuns.Leases reportRunLeases,
            Clock clock) {
        return new ReportRunGenerator(reportRuns, new com.finapp.accounting.reporting.ReportGeneration(
                com.finapp.accounting.reporting.ReportGeneration.FROM_THE_SNAPSHOT, reportRunLeases.generationTimeout(),
                clock),
                reportSnapshots, accountingTransactions, reportRunMetrics);
    }

    @Bean
    ReportRunDesk reportRunDesk(
            com.finapp.accounting.ReportRuns reportRuns,
            ReportRunGenerator reportRunGenerator,
            ReportSnapshots reportSnapshots,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new ReportRunDesk(reportRuns, reportRunGenerator, reportSnapshots, idempotentExecutor,
                accountingTransactions, accountingFlowScope);
    }
}
