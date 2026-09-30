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
            com.finapp.settlement.SettlementBatchStore<Connection> settlementBatchStore,
            com.finapp.reconciliation.SuspenseReadings suspenseReadings,
            com.finapp.payments.UnmatchedConfirmationStore<Connection>
                    unmatchedConfirmationStore) {
        return new PositionProof(
                ledgerAccountStore,
                new com.finapp.ledger.JdbcBalanceDerivation(),
                journalEntryStore,
                expectationReadings,
                settlementSources,
                settlementFileStore,
                settlementBatchStore,
                suspenseReadings,
                unmatchedConfirmationStore);
    }

    /** The suspense proof's and gauges' reads (`P8-TSK-010`, ADR-0070 §§5, 7). */
    @Bean
    com.finapp.reconciliation.SuspenseReadings suspenseReadings() {
        return new com.finapp.reconciliation.JdbcSuspenseReadings();
    }

    /**
     * The raise's writer (`P8-TSK-010`, ADR-0069 §3): converging on the one-open uniques,
     * severity assessed inside from the pinned rule set's threshold, acting-only audit and
     * {@code reconciliation.ReconciliationBreakRaised} through the outbox.
     */
    @Bean
    com.finapp.reconciliation.BreakRegister breakRegister(
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.JdbcBreakRegister(
                outboxWriter, auditWriter, idGenerator);
    }

    /**
     * Park, unpark and the release primitive (`P8-TSK-010`, ADR-0070) — the reconciliation
     * posting path, over the ledger's own {@code PostingService}.
     */
    @Bean
    com.finapp.reconciliation.Suspense suspense(
            com.finapp.ledger.PostingService postingService,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.Suspense(
                postingService, ledgerAccountStore, idGenerator);
    }

    /** The key-collision leg (`P8-TSK-010`); `P8-TSK-013`'s sweep schedules it. */
    @Bean
    com.finapp.reconciliation.KeyCollisionBreaks keyCollisionBreaks(
            com.finapp.reconciliation.BreakRegister breakRegister,
            RuleSets ruleSets,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.KeyCollisionBreaks(
                breakRegister, ruleSets, idGenerator);
    }

    /**
     * The typing lookup (`P8-TSK-010`, ADR-0064): reconciliation declares the port, this
     * composition joins it to payments' and merchant's public read stores — read-only,
     * lock-free, never allocating.
     */
    @Bean
    com.finapp.reconciliation.InternalReferenceLookup internalReferenceLookup(
            com.finapp.payments.PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.payments.RefundStore<Connection> refundStore,
            com.finapp.payments.DisputeStore<Connection> disputeStore,
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore,
            com.finapp.merchant.MerchantPayoutStore<Connection> merchantPayoutStore,
            com.finapp.payments.SchemeExecutionClaimStore<Connection>
                    schemeExecutionClaimStore) {
        return new JdbcInternalReferenceLookup(
                paymentAttemptStore,
                refundStore,
                disputeStore,
                withdrawalStore,
                merchantPayoutStore,
                schemeExecutionClaimStore);
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
            com.finapp.reconciliation.RunReadings runReadings,
            SettlementFileStore<Connection> settlementFileStore,
            SettlementSources settlementSources,
            javax.sql.DataSource dataSource,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new com.finapp.app.telemetry.ReconciliationMetrics(
                positionProof,
                runReadings,
                settlementFileStore,
                settlementSources,
                dataSource::getConnection,
                clock,
                meterRegistry);
    }

    /** The matcher's persistence (`P8-TSK-011`, ADR-0068). */
    @Bean
    com.finapp.reconciliation.MatchingStore matchingStore() {
        return new com.finapp.reconciliation.JdbcMatchingStore();
    }

    /** The pinned rule set's rows and tolerance, read per chunk (`P8-TSK-011`). */
    @Bean
    com.finapp.reconciliation.MatchingRules matchingRules() {
        return new com.finapp.reconciliation.MatchingRules();
    }

    /** The run gauges' reads (`P8-TSK-011`). */
    @Bean
    com.finapp.reconciliation.RunReadings runReadings() {
        return new com.finapp.reconciliation.JdbcRunReadings();
    }

    /**
     * The resolutions' writer (`P8-TSK-012`, ADR-0071): the platform's {@code EVIDENCED}
     * kind only — born {@code APPROVED} in the transaction whose zero-residual
     * allocation or offset explained the break; the person kinds arrive with
     * `P8-TSK-015`'s door.
     */
    @Bean
    com.finapp.reconciliation.Resolutions resolutions(
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.JdbcResolutions(
                outboxWriter, auditWriter, idGenerator);
    }

    /** One transaction per chunk — the run leg's containment (the parse leg's shape). */
    @Bean
    com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner(
            TransactionTemplate reconciliationTransactions, javax.sql.DataSource dataSource) {
        return new com.finapp.reconciliation.TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return reconciliationTransactions.execute(
                        status -> {
                            Connection unitOfWork =
                                    org.springframework.jdbc.datasource.DataSourceUtils
                                            .getConnection(dataSource);
                            try {
                                return work.apply(unitOfWork);
                            } finally {
                                org.springframework.jdbc.datasource.DataSourceUtils
                                        .releaseConnection(unitOfWork, dataSource);
                            }
                        });
            }
        };
    }

    /**
     * The run leg (`P8-TSK-011`, ADR-0068 §§3–6): per source under the namespace-4
     * try-lock, one transaction per chunk, decisions and allocations from locked snapshots,
     * the remainders parked through `P8-TSK-010`'s suspense with their breaks.
     */
    @Bean
    com.finapp.reconciliation.Matching matching(
            com.finapp.reconciliation.MatchingStore matchingStore,
            com.finapp.reconciliation.MatchingRules matchingRules,
            com.finapp.reconciliation.BreakRegister breakRegister,
            com.finapp.reconciliation.Suspense suspense,
            com.finapp.reconciliation.Resolutions resolutions,
            com.finapp.reconciliation.InternalReferenceLookup internalReferenceLookup,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.matching.chunk:200}")
                    int chunkSize,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.matching.block-after:3}")
                    int blockAfterFailures,
            com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner) {
        return new com.finapp.reconciliation.Matching(
                matchingStore,
                matchingRules,
                breakRegister,
                suspense,
                resolutions,
                internalReferenceLookup,
                ledgerAccountStore,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock,
                new com.finapp.reconciliation.Matching.Config(chunkSize, blockAfterFailures),
                reconciliationTransactionRunner);
    }

    /**
     * The matcher's schedule — leaderless on every instance, off in test contexts (the
     * relay's flag discipline); registered in {@code DISTRIBUTED_EXECUTION.md} §3.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.reconciliation.matching.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ReconciliationSchedule reconciliationSchedule(
            com.finapp.reconciliation.Matching matching,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.matching.poll:PT15S}")
                    java.time.Duration pollInterval) {
        return new ReconciliationSchedule(matching, pollInterval);
    }

    /**
     * Time's observers (`P8-TSK-013`): ageing, severity escalation, lost-block detection
     * and the scheduled `P8-TSK-010` key-collision leg — every write a conditional whose
     * racers converge.
     */
    @Bean
    com.finapp.reconciliation.ReconciliationSweep reconciliationSweep(
            com.finapp.reconciliation.MatchingStore matchingStore,
            com.finapp.reconciliation.BreakRegister breakRegister,
            com.finapp.reconciliation.KeyCollisionBreaks keyCollisionBreaks,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.sweep.batch:200}")
                    int batch,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.matching.block-after:3}")
                    int blockAfterFailures,
            com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner) {
        return new com.finapp.reconciliation.ReconciliationSweep(
                matchingStore,
                breakRegister,
                keyCollisionBreaks,
                outboxWriter,
                idGenerator,
                clock,
                new com.finapp.reconciliation.ReconciliationSweep.Config(
                        batch, blockAfterFailures),
                reconciliationTransactionRunner);
    }

    /**
     * The observers' schedule — leaderless on every instance, off in test contexts (the
     * relay's flag discipline); registered in {@code DISTRIBUTED_EXECUTION.md} §3.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "finapp.reconciliation.sweep.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ReconciliationSweepSchedule reconciliationSweepSchedule(
            com.finapp.reconciliation.ReconciliationSweep reconciliationSweep,
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.sweep.poll:PT60S}")
                    java.time.Duration pollInterval) {
        return new ReconciliationSweepSchedule(reconciliationSweep, pollInterval);
    }

    /**
     * Whether this instance runs the reconciliation sweep — eager either way, so "off"
     * reads as {@code 0} rather than as a missing series (`P1-TSK-029`'s rule).
     */
    @Bean
    io.micrometer.core.instrument.Gauge reconciliationSweepEnabled(
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.sweep.enabled:true}")
                    boolean enabled,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return io.micrometer.core.instrument.Gauge.builder(
                        "finapp.reconciliation.sweep.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the reconciliation sweep")
                .register(meterRegistry);
    }

    /**
     * Whether this instance runs the matching sweeper — eager either way, so "off" reads as
     * {@code 0} rather than as a missing series (`P1-TSK-029`'s rule).
     */
    @Bean
    io.micrometer.core.instrument.Gauge reconciliationMatchingSweeperEnabled(
            @org.springframework.beans.factory.annotation.Value(
                            "${finapp.reconciliation.matching.sweeper.enabled:true}")
                    boolean enabled,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return io.micrometer.core.instrument.Gauge.builder(
                        "finapp.reconciliation.matching.sweeper.enabled",
                        () -> enabled ? 1 : 0)
                .description("Whether this instance runs the reconciliation matching sweeper")
                .register(meterRegistry);
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

    // ------------------------------------------------------------------ the desk (P8-TSK-014)

    /**
     * The investigator's reads' transaction shape (`P8-TSK-014`): {@code REQUIRES_NEW},
     * {@code REPEATABLE READ}, read-only — a trace, a case file or a settlement status and its
     * trail are one snapshot of the books, set by the transaction manager (and reset by it)
     * rather than on a borrowed connection.
     */
    @Bean
    TransactionTemplate reconciliationSnapshotReads(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setReadOnly(true);
        return template;
    }

    /**
     * The case file's view beyond reconciliation — link targets and the trace's external
     * steps — over settlement's, the ledger's and payments' public read stores (ADR-0064).
     */
    @Bean
    ComposedCaseFileEvidence composedCaseFileEvidence(
            SettlementFileStore<Connection> settlementFileStore,
            com.finapp.settlement.SettlementBatchStore<Connection> settlementBatchStore,
            JournalEntryStore<Connection> journalEntryStore,
            com.finapp.payments.ProviderEvidenceStore<Connection> providerEvidenceStore,
            com.finapp.payments.DisputeStore<Connection> disputeStore,
            com.finapp.payments.UnmatchedConfirmationStore<Connection>
                    unmatchedConfirmationStore) {
        return new ComposedCaseFileEvidence(
                settlementFileStore,
                settlementBatchStore,
                journalEntryStore,
                providerEvidenceStore,
                disputeStore,
                unmatchedConfirmationStore);
    }

    /** The investigation as the break's case file (`P8-TSK-014`, ADR-0069 §7). */
    @Bean
    com.finapp.reconciliation.BreakCaseFile breakCaseFile(
            ComposedCaseFileEvidence composedCaseFileEvidence,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.reconciliation.BreakCaseFile(
                new com.finapp.reconciliation.JdbcBreakCaseStore(),
                composedCaseFileEvidence,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock);
    }

    /** The investigator's desk (`P8-TSK-014`): reads, the case file, trace and status. */
    @Bean
    BreakInvestigation breakInvestigation(
            com.finapp.reconciliation.BreakCaseFile breakCaseFile,
            ComposedCaseFileEvidence composedCaseFileEvidence,
            com.finapp.platform.idempotency.IdempotentExecutor idempotentExecutor,
            TransactionTemplate reconciliationTransactions,
            TransactionTemplate reconciliationSnapshotReads,
            javax.sql.DataSource dataSource) {
        com.finapp.reconciliation.JdbcBreakInquiries breakInquiries =
                new com.finapp.reconciliation.JdbcBreakInquiries();
        com.finapp.reconciliation.JdbcExpectationInquiries expectationInquiries =
                new com.finapp.reconciliation.JdbcExpectationInquiries();
        return new BreakInvestigation(
                breakCaseFile,
                breakInquiries,
                new com.finapp.reconciliation.BreakTraces(breakInquiries, composedCaseFileEvidence),
                expectationInquiries,
                new com.finapp.reconciliation.SettlementStatuses(expectationInquiries),
                idempotentExecutor,
                reconciliationTransactions,
                reconciliationSnapshotReads,
                dataSource);
    }
}
