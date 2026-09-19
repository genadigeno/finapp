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
 * stance). The posting command's predicted consumer arrived with the transfer surface
 * (`P4-TSK-008` — {@code TransferBeans} composes it into the execution command), so it is
 * beaned below; the reversal command's arrived the same way (`P4-TSK-009` — the transfer
 * reversal is its first composition-root consumer, expiring the {@code P1-TSK-007} licence
 * exactly as this sentence used to predict).
 */
@Configuration
public class LedgerBeans {

    /**
     * The one write path ({@code INV-LED-04}), observed by the real {@code PostingObserver}
     * bean — the required-parameter design (`P3-TSK-020`) forcing exactly this wiring decision:
     * a transfer's posting counts on {@code finapp.ledger.posting} like every other.
     */
    @Bean
    com.finapp.ledger.PostingService postingService(
            IdempotentExecutor idempotentExecutor,
            JournalEntryStore<Connection> journalEntryStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            BalanceProjection<Connection> balanceProjection,
            com.finapp.sharedkernel.id.IdGenerator ids,
            Clock clock,
            com.finapp.ledger.PostingObserver postingObserver) {
        return new com.finapp.ledger.PostingService(
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
     * The reversal command (`P3-TSK-016`), beaned by its first composition-root consumer —
     * `P4-TSK-009`'s transfer reversal — with the same shared collaborators and the same real
     * {@code PostingObserver} as its two siblings, so a transfer's reversal counts on
     * {@code finapp.ledger.posting} like every other journal write.
     */
    @Bean
    com.finapp.ledger.ReversalService reversalService(
            IdempotentExecutor idempotentExecutor,
            JournalEntryStore<Connection> journalEntryStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            BalanceProjection<Connection> balanceProjection,
            com.finapp.sharedkernel.id.IdGenerator ids,
            Clock clock,
            com.finapp.ledger.PostingObserver postingObserver) {
        return new com.finapp.ledger.ReversalService(
                idempotentExecutor,
                journalEntryStore,
                auditWriter,
                outboxWriter,
                balanceProjection,
                ids,
                clock,
                postingObserver);
    }

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
                new com.finapp.ledger.JdbcAdjustmentProposalStore(),
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
