package com.finapp.app.reconciliation;

import com.finapp.ledger.JournalEntryStore;
import com.finapp.reconciliation.ExpectationReadings;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.JdbcExpectationReadings;
import com.finapp.reconciliation.JdbcExpectationRegister;
import com.finapp.reconciliation.JdbcRuleSets;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation module's composition (`P8-TSK-004`, ADR-0064, ADR-0067) — and the ONE
 * place `payments`' and `merchant`'s expectation ports meet `reconciliation`'s register: none of
 * them may compile against another, so the recorder below is the join, on the completing
 * connection, in the completing transaction.
 */
@Configuration
public class ReconciliationBeans {

    @Bean
    ExpectationRegister expectationRegister(IdGenerator idGenerator) {
        return new JdbcExpectationRegister(idGenerator);
    }

    @Bean
    RuleSets ruleSets() {
        return new JdbcRuleSets();
    }

    /** The run's birth writer (`P8-TSK-009`); the run leg (`P8-TSK-011`) drives it. */
    @Bean
    com.finapp.reconciliation.ReconciliationRuns reconciliationRuns() {
        return new com.finapp.reconciliation.JdbcReconciliationRuns();
    }

    /** The items' birth writer (`P8-TSK-009`); the matcher (`P8-TSK-011`) disposes of them. */
    @Bean
    com.finapp.reconciliation.ExternalItems externalItems() {
        return new com.finapp.reconciliation.JdbcExternalItems();
    }

    /**
     * The ONE implementation of both expectation ports — {@code payments.SettlementExpectations}
     * and {@code merchant.PayoutSettlementExpectations} (`P8-TSK-005`): typed as the recorder so
     * each port's injection point resolves to this one bean. Source resolved from the declared
     * position through the compiled register (`INV-SET-05`), amount, direction and date read off
     * the posted entry, the dating pinned from the source's ACTIVE rule set
     * ({@code INV-HIST-04}).
     */
    @Bean
    ExpectationReadings<Connection> expectationReadings() {
        return new JdbcExpectationReadings();
    }

    /**
     * The reconciliation commands' transaction shape (`P8-TSK-007`): {@code REQUIRES_NEW},
     * default isolation — the backfill's pages are inserts converging on uniques, and the
     * report sets its own {@code REPEATABLE READ} on the connection it holds.
     */
    @Bean
    TransactionTemplate reconciliationTransactions(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    /**
     * The position proof and completeness verifier (`P8-TSK-007`, ADR-0067 §9): report-only,
     * composed over the ledger's derivation and line reads, reconciliation's readings and
     * the settlement register — the metrics and the positions report both read it.
     */
    @Bean
    PositionProof positionProof(
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            JournalEntryStore<Connection> journalEntryStore,
            ExpectationReadings<Connection> expectationReadings,
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            com.finapp.settlement.SettlementBatchStore<Connection> settlementBatchStore) {
        return new PositionProof(
                ledgerAccountStore,
                new com.finapp.ledger.JdbcBalanceDerivation(),
                journalEntryStore,
                expectationReadings,
                settlementSources,
                settlementFileStore,
                settlementBatchStore);
    }

    /**
     * The opening-position backfill (`P8-TSK-007`, ADR-0067 §8): history adopted through the
     * live recorder's own path, page by page, converging on the register's uniques — and
     * since `P8-TSK-009` the accepted batches' remittances re-derived through the live
     * intake's own opener, so the register stays rebuildable from the books alone.
     */
    @Bean
    OpeningPosition openingPosition(
            com.finapp.payments.PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.payments.PaymentIntentStore<Connection> paymentIntentStore,
            com.finapp.payments.RefundStore<Connection> refundStore,
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.payments.DisputeStore<Connection> disputeStore,
            com.finapp.payments.UnmatchedConfirmationStore<Connection>
                    unmatchedConfirmationStore,
            com.finapp.merchant.MerchantPayoutStore<Connection> merchantPayoutStore,
            com.finapp.payments.PaymentRails paymentRails,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            JournalEntryStore<Connection> journalEntryStore,
            ReconciliationExpectationRecorder settlementExpectations,
            com.finapp.platform.idempotency.IdempotentExecutor idempotentExecutor,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate reconciliationTransactions,
            javax.sql.DataSource dataSource,
            SettlementSources settlementSources,
            com.finapp.settlement.SettlementBatchStore<Connection> settlementBatchStore,
            com.finapp.app.settlement.ReconciliationIntake acceptedBatchIntake) {
        return new OpeningPosition(
                paymentAttemptStore,
                paymentIntentStore,
                refundStore,
                withdrawalStore,
                disputeStore,
                unmatchedConfirmationStore,
                new com.finapp.payments.JdbcClearingRecordStore(),
                merchantPayoutStore,
                paymentRails,
                new com.finapp.ledger.ChartOfAccounts<>(ledgerAccountStore),
                journalEntryStore,
                settlementExpectations,
                idempotentExecutor,
                auditWriter,
                idGenerator,
                clock,
                reconciliationTransactions,
                dataSource,
                settlementSources,
                settlementBatchStore,
                acceptedBatchIntake);
    }

    /**
     * The reconciliation verdict gauges (`P8-TSK-007`, `PHASE_8_PLAN.md` §15), over the
     * application's own {@code DataSource} — the {@code SettlementFileMetrics} reasoning.
     */
    @Bean
    com.finapp.app.telemetry.ReconciliationMetrics reconciliationMetrics(
            PositionProof positionProof,
            SettlementSources settlementSources,
            javax.sql.DataSource dataSource,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new com.finapp.app.telemetry.ReconciliationMetrics(
                positionProof,
                settlementSources,
                dataSource::getConnection,
                clock,
                meterRegistry);
    }

    @Bean
    ReconciliationExpectationRecorder settlementExpectations(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            JournalEntryStore<Connection> journalEntryStore,
            RuleSets ruleSets,
            ExpectationRegister expectationRegister,
            Clock clock) {
        return new ReconciliationExpectationRecorder(
                settlementSources,
                settlementFileStore,
                journalEntryStore,
                ruleSets,
                expectationRegister,
                clock);
    }
}
