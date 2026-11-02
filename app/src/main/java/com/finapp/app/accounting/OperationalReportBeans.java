package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.CloseStatusReads;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.OperationalDoorBound;
import com.finapp.accounting.OperationalReports;
import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.reporting.CloseStatusDefinition;
import com.finapp.accounting.reporting.GlActivityDefinition;
import com.finapp.accounting.reporting.PurposeReportDefinition;
import com.finapp.accounting.reporting.ReconciliationStatusDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.SuspenseDefinition;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the operational financial reports (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 13.6) - its own configuration
 * beside {@link TrialBalanceBeans}, so the module's parallel tasks each add theirs without editing one file: the ten definitions, which
 * {@code AccountingBeans.reportDefinitions} collects; the ports they read through, all {@code SELECT} only, on the caller's snapshot;
 * the door the two operational ones are read at; and the stalled-run gauge, which is declared whether or not the schedule runs.
 */
@Configuration(proxyBeanMethods = false)
public class OperationalReportBeans {

    // ------------------------------------------------------------------ the journal's

    /** The counterparty's dimension the trial balance's statements do not carry: nothing nets between them. */
    @Bean
    CounterpartyPositionReads counterpartyPositionReads() {
        return new LedgerCounterpartyPositionReads();
    }

    @Bean
    GlActivityDefinition glActivityDefinition(TrialBalanceReads trialBalanceReads, CounterpartyPositionReads counterpartyPositionReads) {
        return new GlActivityDefinition(trialBalanceReads, counterpartyPositionReads);
    }

    @Bean
    PurposeReportDefinition revenueDefinition(TrialBalanceReads trialBalanceReads) {
        return PurposeReportDefinition.revenue(trialBalanceReads);
    }

    @Bean
    PurposeReportDefinition receivablesPayablesDefinition(TrialBalanceReads trialBalanceReads) {
        return PurposeReportDefinition.receivablesPayables(trialBalanceReads);
    }

    @Bean
    PurposeReportDefinition settlementPositionsDefinition(TrialBalanceReads trialBalanceReads,
            CounterpartyPositionReads counterpartyPositionReads) {
        return PurposeReportDefinition.settlementPositions(trialBalanceReads, counterpartyPositionReads);
    }

    @Bean
    PurposeReportDefinition fxResultsDefinition(TrialBalanceReads trialBalanceReads) {
        return PurposeReportDefinition.fxResults(trialBalanceReads);
    }

    @Bean
    PurposeReportDefinition loanPortfolioDefinition(TrialBalanceReads trialBalanceReads) {
        return PurposeReportDefinition.loanPortfolio(trialBalanceReads);
    }

    @Bean
    PurposeReportDefinition bnplExposureDefinition(TrialBalanceReads trialBalanceReads) {
        return PurposeReportDefinition.bnplExposure(trialBalanceReads);
    }

    // ------------------------------------------------------------------ reconciliation's

    /** The month's runs and the frozen suspense register - {@code SELECT} only, on the caller's snapshot. */
    @Bean
    ReconciliationMonthReads reconciliationMonthReads(ReportReadings reconciliationReportReadings) {
        return new ReconciliationMonthReads(reconciliationReportReadings);
    }

    @Bean
    PastTheCutoffPostings pastTheCutoffPostings() {
        return new LedgerFlooredPostings();
    }

    @Bean
    SuspenseDefinition suspenseDefinition(TrialBalanceReads trialBalanceReads, ReconciliationMonthReads reconciliationMonthReads) {
        return new SuspenseDefinition(trialBalanceReads, reconciliationMonthReads);
    }

    @Bean
    ReconciliationStatusDefinition reconciliationStatusDefinition(ReconciliationDisclosure reconciliationDisclosure,
            ReconciliationMonthReads reconciliationMonthReads, PastTheCutoffPostings pastTheCutoffPostings) {
        return new ReconciliationStatusDefinition(reconciliationDisclosure, reconciliationMonthReads, pastTheCutoffPostings);
    }

    // ------------------------------------------------------------------ the close's

    /**
     * How long a {@code LOCKED} period may stand with no run: {@code finapp.accounting.close.run-stalled-polls} (default 10) polls
     * of the check leg - refused at start-up under three, so the ordinary gap cannot trip it. Declared apart from the schedule,
     * which a test context or an operator can switch off: this is what must speak when it is.
     */
    @Bean
    CloseRunStall closeRunStall(
            @Value("${finapp.accounting.close.checks-poll:PT30S}") Duration checksPoll,
            @Value("${finapp.accounting.close.run-stalled-polls:" + CloseRunStall.STANDARD_POLLS + "}") int polls) {
        return new CloseRunStall(checksPoll, polls);
    }

    @Bean
    CloseStatusReads closeStatusReads(AccountingPeriodStore accountingPeriodStore, CloseRuns closeRuns,
            AccountingBreakStore accountingBreakStore, ValuationRateSetStore valuationRateSetStore) {
        return new AccountingCloseStatusReads(accountingPeriodStore, closeRuns, accountingBreakStore, valuationRateSetStore);
    }

    @Bean
    CloseStatusDefinition closeStatusDefinition(CloseStatusReads closeStatusReads, CloseRunStall closeRunStall) {
        return new CloseStatusDefinition(closeStatusReads, closeRunStall);
    }

    @Bean
    CloseRunStallMetrics closeRunStallMetrics(CloseRunStore closeRunStore, CloseRunStall closeRunStall, DataSource dataSource,
            Clock clock, MeterRegistry meterRegistry) {
        return new CloseRunStallMetrics(closeRunStore, closeRunStall, dataSource::getConnection, clock, meterRegistry);
    }

    // ------------------------------------------------------------------ the door

    /**
     * What the door may hold of this instance: {@code finapp.accounting.report.operational-timeout} (default 30 seconds) per read and
     * {@code finapp.accounting.report.operational-permits} (default 2) at once - never the report runs' generation budget, which sits
     * under a lease and was sized for a run nobody waits on.
     */
    @Bean
    OperationalDoorBound operationalDoorBound(
            @Value("${finapp.accounting.report.operational-timeout:" + OperationalDoorBound.STANDARD_TIMEOUT + "}") Duration timeout,
            @Value("${finapp.accounting.report.operational-permits:" + OperationalDoorBound.STANDARD_PERMITS + "}") int permits) {
        return new OperationalDoorBound(timeout, permits);
    }

    @Bean
    OperationalReports operationalReports(ReportDefinitions reportDefinitions, AccountingPeriodStore accountingPeriodStore,
            OperationalDoorBound operationalDoorBound, AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new OperationalReports(reportDefinitions, accountingPeriodStore, new ReportGeneration(
                ReportGeneration.FROM_THE_SNAPSHOT, operationalDoorBound.timeout(), clock), auditWriter, idGenerator, clock);
    }

    /** The door's snapshots, armed at its bound: the shared {@code ReportSnapshots} bean is armed at the runs' four minutes. */
    static ReportSnapshots doorSnapshots(JdbcReportSnapshots.Connections connections, OperationalDoorBound bound) {
        return new JdbcReportSnapshots(connections, bound.timeout());
    }

    @Bean
    OperationalReportDesk operationalReportDesk(OperationalReports operationalReports, DataSource dataSource,
            AccountingTransactions accountingTransactions, AccountingFlowScope accountingFlowScope,
            OperationalDoorBound operationalDoorBound) {
        return new OperationalReportDesk(operationalReports, doorSnapshots(dataSource::getConnection, operationalDoorBound),
                accountingTransactions, accountingFlowScope, operationalDoorBound);
    }
}
