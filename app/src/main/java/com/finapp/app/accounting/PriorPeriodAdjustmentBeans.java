package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.JdbcPriorPeriodAdjustmentStore;
import com.finapp.accounting.PriorPeriodAdjustmentStore;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the prior-period adjustments (`P14-TSK-013`) - its own configuration beside {@link PeriodCloseBeans}: the store, the port over the ledger's accounting-owned door, the machine and the desk.
 */
@Configuration(proxyBeanMethods = false)
public class PriorPeriodAdjustmentBeans {

    @Bean
    PriorPeriodAdjustmentStore priorPeriodAdjustmentStore() {
        return new JdbcPriorPeriodAdjustmentStore();
    }

    @Bean
    PriorPeriodPostings priorPeriodPostings(AdjustmentService adjustmentService, LedgerAccountStore<Connection> ledgerAccountStore) {
        return new LedgerPriorPeriodPostings(adjustmentService, ledgerAccountStore);
    }

    @Bean
    PriorPeriodAdjustments priorPeriodAdjustments(
            AccountingPeriodStore accountingPeriodStore,
            PriorPeriodAdjustmentStore priorPeriodAdjustmentStore,
            PriorPeriodPostings priorPeriodPostings,
            GlMappingStore glMappingStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new PriorPeriodAdjustments(accountingPeriodStore, priorPeriodAdjustmentStore, priorPeriodPostings, glMappingStore, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    PriorPeriodAdjustmentDesk priorPeriodAdjustmentDesk(
            PriorPeriodAdjustments priorPeriodAdjustments,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new PriorPeriodAdjustmentDesk(priorPeriodAdjustments, idempotentExecutor, accountingTransactions, accountingFlowScope);
    }
}
