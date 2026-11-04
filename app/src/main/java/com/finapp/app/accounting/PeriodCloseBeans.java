package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.JdbcPeriodCloseStore;
import com.finapp.accounting.PeriodCloseStore;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the four-eyes close and the reopen (`P14-TSK-012`) - its own configuration beside {@link AccountingCloseBeans}, so the module's parallel tasks each add theirs without editing one file: the store, the machine and its desk.
 */
@Configuration(proxyBeanMethods = false)
public class PeriodCloseBeans {

    @Bean
    PeriodCloseStore periodCloseStore() {
        return new JdbcPeriodCloseStore();
    }

    @Bean
    PeriodCloses periodCloses(
            AccountingPeriodStore accountingPeriodStore,
            PeriodCloseStore periodCloseStore,
            CloseRuns closeRuns,
            AccountingBreakStore accountingBreakStore,
            ValuationRateSetStore valuationRateSetStore,
            ReconciliationRunCoverage reconciliationRunCoverage,
            PostingCutoff postingCutoff,
            ReportRuns reportRuns,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new PeriodCloses(accountingPeriodStore, periodCloseStore, closeRuns, accountingBreakStore, valuationRateSetStore, reconciliationRunCoverage, postingCutoff, reportRuns,
                auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    PeriodCloseDesk periodCloseDesk(
            PeriodCloses periodCloses,
            PriorPeriodAdjustments priorPeriodAdjustments,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new PeriodCloseDesk(periodCloses, priorPeriodAdjustments, idempotentExecutor, accountingTransactions, accountingFlowScope);
    }
}
