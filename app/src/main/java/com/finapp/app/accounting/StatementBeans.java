package com.finapp.app.accounting;

import com.finapp.accounting.CloseRecordReads;
import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.ReconciliationAsOfCutoff;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the financial statements (`P14-TSK-016`; {@code PHASE_14_PLAN.md} section 13.6) - its own configuration beside {@link TrialBalanceBeans} and {@link OperationalReportBeans}, so the module's parallel tasks each add theirs without editing one file: the two definitions, which
 * {@code AccountingBeans.reportDefinitions} collects; the ports their notes read through, all {@code SELECT} only, on the run's snapshot; and the door a figure's lines are read at.
 */
@Configuration(proxyBeanMethods = false)
public class StatementBeans {

    /** What corrected earlier months in a month (`P14-TSK-013`): built for these notes, its one consumer. */
    @Bean
    PriorPeriodCorrections priorPeriodCorrections() {
        return new LedgerPriorPeriodCorrections();
    }

    @Bean
    ReconciliationAsOfCutoff reconciliationAsOfCutoff(ReportReadings reconciliationReportReadings) {
        return new ReconciliationAsOfCutoffReads(reconciliationReportReadings);
    }

    @Bean
    CloseRecordReads closeRecordReads() {
        return new AccountingCloseRecordReads();
    }

    @Bean
    StatementNotes statementNotes(ReconciliationMonthReads reconciliationMonthReads, ReconciliationAsOfCutoff reconciliationAsOfCutoff, PriorPeriodCorrections priorPeriodCorrections,
            CloseRecordReads closeRecordReads) {
        return new StatementNotes(reconciliationMonthReads, reconciliationAsOfCutoff, priorPeriodCorrections, closeRecordReads);
    }

    @Bean
    BalanceSheetDefinition balanceSheetDefinition(TrialBalanceReads trialBalanceReads, CounterpartyPositionReads counterpartyPositionReads, ValuationRateReads valuationRateReads,
            StatementNotes statementNotes) {
        return new BalanceSheetDefinition(trialBalanceReads, counterpartyPositionReads, valuationRateReads, statementNotes);
    }

    @Bean
    IncomeStatementDefinition incomeStatementDefinition(TrialBalanceReads trialBalanceReads, CounterpartyPositionReads counterpartyPositionReads, ValuationRateReads valuationRateReads,
            StatementNotes statementNotes) {
        return new IncomeStatementDefinition(trialBalanceReads, counterpartyPositionReads, valuationRateReads, statementNotes);
    }

    @Bean
    ReportRunLinesDesk reportRunLinesDesk(ReportRuns reportRuns, GlMappingStore glMappingStore, GeneralLedger generalLedger, GlSnapshots glSnapshots, AccountingTransactions accountingTransactions,
            AuditWriter<Connection> auditWriter, AccountingFlowScope accountingFlowScope, IdGenerator idGenerator, Clock clock) {
        return new ReportRunLinesDesk(reportRuns, glMappingStore, new JdbcPeriodPins(), generalLedger, glSnapshots, accountingTransactions, auditWriter, accountingFlowScope, idGenerator, clock);
    }
}
