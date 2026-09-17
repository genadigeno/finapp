package com.finapp.app.ledger;

import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the ledger's one public surface (`P3-TSK-017`): the adjustment.
 *
 * <p>The stores are stateless, so direct instances are the wiring (the {@code AccountsBeans}
 * stance); the posting and reversal commands stay unwired deliberately — they are in-process
 * APIs whose consumers are Phase 4's flows, and a bean with no consumer is the
 * {@code P1-TSK-007} licence declined.
 */
@Configuration
public class LedgerBeans {

    @Bean
    JournalEntryStore<Connection> journalEntryStore(com.finapp.sharedkernel.id.IdGenerator ids) {
        return new JdbcJournalEntryStore(ids);
    }

    @Bean
    BalanceProjection<Connection> balanceProjection() {
        return new JdbcBalanceProjection();
    }

    @Bean
    AdjustmentService adjustmentService(
            IdempotentExecutor idempotentExecutor,
            JournalEntryStore<Connection> journalEntryStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            BalanceProjection<Connection> balanceProjection,
            com.finapp.sharedkernel.id.IdGenerator ids,
            Clock clock,
            com.finapp.ledger.PostingObserver postingObserver) {
        return new AdjustmentService(
                idempotentExecutor,
                journalEntryStore,
                auditWriter,
                outboxWriter,
                balanceProjection,
                ids,
                clock,
                postingObserver);
    }

    /**
     * The adjustment transaction: {@code REQUIRES_NEW}, default isolation — the entry is an
     * insert under {@code READ COMMITTED} (ADR-0039), and the idempotency claim is the
     * contended write, arbitrated by its unique constraint.
     */
    @Bean
    TransactionTemplate ledgerTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    LedgerAdjustments ledgerAdjustments(
            AdjustmentService adjustmentService,
            TransactionTemplate ledgerTransactions,
            DataSource dataSource) {
        return new LedgerAdjustments(adjustmentService, ledgerTransactions, dataSource);
    }
}
