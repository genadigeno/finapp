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
                unmatchedConfirmationStore,
                new com.finapp.ledger.JdbcCounterpartyStore());
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
            IdGenerator idGenerator,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters) {
        // finapp.reconciliation.break.raised at the one door (P8-TSK-024).
        return new com.finapp.app.telemetry.MeteredBreakRegister(
                new com.finapp.reconciliation.JdbcBreakRegister(
                        outboxWriter, auditWriter, idGenerator),
                reconciliationOutcomeMeters);
    }

    /**
     * The domain spans (`P8-TSK-024`, `PHASE_8_PLAN.md` §15): the legs' units of work, identifier
     * attributes only, over the platform's tracer when one exists.
     */
    @Bean
    com.finapp.platform.telemetry.Spans domainSpans(
            org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracers) {
        return new com.finapp.app.telemetry.TracerSpans(tracers);
    }

    /** Reconciliation's counters and timers, counted after commit (`P8-TSK-024`). */
    @Bean
    com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters(
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            javax.sql.DataSource dataSource,
            Clock clock,
            com.finapp.platform.telemetry.Spans domainSpans) {
        return new com.finapp.app.telemetry.ReconciliationOutcomeMeters(
                meterRegistry,
                settlementSources,
                new com.finapp.app.telemetry.SeededSourceCodes(
                        settlementFileStore, dataSource, clock),
                domainSpans);
    }

    /** The open breaks' gauges (`P8-TSK-024`): per type and severity, and the oldest age. */
    @Bean
    com.finapp.app.telemetry.BreakMetrics breakMetrics(
            javax.sql.DataSource dataSource,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new com.finapp.app.telemetry.BreakMetrics(
                new com.finapp.reconciliation.JdbcBreakReadings(),
                dataSource::getConnection,
                clock,
                meterRegistry);
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

    /**
     * Statement continuity (`P8-TSK-016`, {@code INV-SET-06}): judged inside each bank
     * statement's acceptance by the intake.
     */
    @Bean
    com.finapp.reconciliation.StatementChain statementChain(
            com.finapp.reconciliation.BreakRegister breakRegister,
            com.finapp.reconciliation.Resolutions resolutions,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.StatementChain(
                breakRegister, resolutions, idGenerator);
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
     * The payout returns waiting for the MERCHANT worker (`P8-TSK-019`) - reconciliation's read, scoped
     * since `P9-TSK-014` to the sources settling {@code PAYOUT_CLEARING}, so the merchant
     * {@code PayoutReturnSweep} never sees a corridor return (ADR-0082). The corridor's reader is the
     * same composition over {@code CORRIDOR_CLEARING} ({@link #waitingReturnsOf}), wired with its
     * worker (`P9-TSK-023`).
     */
    @Bean
    com.finapp.reconciliation.WaitingPayoutReturns waitingPayoutReturns(
            com.finapp.settlement.SettlementFileStore<Connection> settlementFileStore,
            com.finapp.settlement.SettlementSources settlementSources) {
        return waitingReturnsOf(
                com.finapp.merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE,
                settlementFileStore,
                settlementSources);
    }

    /**
     * The payout returns waiting for the CORRIDOR worker (`P9-TSK-023`): the sources settling the
     * declared corridor rails' position, read off the rail declarations - never hand-named.
     */
    @Bean
    com.finapp.reconciliation.WaitingPayoutReturns waitingCorridorReturns(
            com.finapp.settlement.SettlementFileStore<Connection> settlementFileStore,
            com.finapp.settlement.SettlementSources settlementSources) {
        com.finapp.ledger.AccountPurpose position = com.finapp.app.payments.PaymentBeans.CORRIDOR_DECLARATIONS.stream()
                .map(declaration -> com.finapp.app.payments.PaymentBeans.DECLARED_RAILS
                        .capabilitiesOf(declaration.rail()).clearingPurpose().orElseThrow())
                .distinct()
                .reduce((one, other) -> {
                    throw new IllegalStateException("the corridor rails settle more than one position");
                })
                .orElseThrow(() -> new IllegalStateException("no corridor rail is declared"));
        return waitingReturnsOf(position, settlementFileStore, settlementSources);
    }

    /**
     * The waiting-return reader of one source family (`P9-TSK-014`): the sources whose compiled
     * descriptor settles {@code position} (any counterparty), matched to their seeded rows by code on
     * every call - read off the register, never hand-named.
     */
    static com.finapp.reconciliation.WaitingPayoutReturns waitingReturnsOf(
            com.finapp.ledger.AccountPurpose position,
            com.finapp.settlement.SettlementFileStore<Connection> settlementFileStore,
            com.finapp.settlement.SettlementSources settlementSources) {
        java.util.Set<String> codes = settlementSources.declared().stream()
                .filter(source -> source.settledPosition().equals(java.util.Optional.of(position)))
                .map(com.finapp.settlement.SettlementSourceDescriptor::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new com.finapp.reconciliation.JdbcWaitingPayoutReturns(
                unitOfWork -> settlementFileStore.sources(unitOfWork).stream()
                        .filter(row -> codes.contains(row.code()))
                        .map(row -> row.id())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    /**
     * A payout return's person fallback, read by the return worker under the payout's row lock
     * (the Phase 8 -> 9 transition, IDEM-1) - reconciliation's own statement.
     */
    @Bean
    com.finapp.reconciliation.PayoutReturnFallbacks payoutReturnFallbacks() {
        return new com.finapp.reconciliation.JdbcPayoutReturnFallbacks();
    }

    /**
     * The payout a parked return names, locked through merchant's own applier - the very row
     * the return worker serialises on - and whether its return stands (the Phase 8 -> 9
     * transition, IDEM-1): the resolution machine binds the person's fallback transfer to it.
     */
    @Bean
    com.finapp.reconciliation.ReturnedPayouts returnedPayouts(
            com.finapp.merchant.PayoutReturns payoutReturns) {
        return (unitOfWork, providerReference, ourReference) ->
                payoutReturns.lockReturnState(unitOfWork, providerReference, ourReference)
                        .map(state -> new com.finapp.reconciliation.ReturnedPayouts.LockedPayout(
                                state.payout().value().toString(), state.returned(),
                                JdbcInternalReferenceLookup.classifyPayout(state.status())));
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
                    schemeExecutionClaimStore,
            com.finapp.settlement.SettlementFileStore<Connection> settlementFileStore,
            com.finapp.settlement.SettlementSources settlementSources,
            com.finapp.payments.PaymentRails paymentRails) {
        return new JdbcInternalReferenceLookup(
                paymentAttemptStore,
                refundStore,
                disputeStore,
                withdrawalStore,
                merchantPayoutStore,
                schemeExecutionClaimStore,
                // A source's rail (P8-TSK-017): its seeded row's code, the compiled descriptor's
                // settled position, and the ONE declared rail whose clearing purpose that is -
                // the register composed here, so reconciliation never names a rail.
                (unitOfWork, sourceId) ->
                        settlementFileStore.sources(unitOfWork).stream()
                                .filter(row -> row.id().equals(sourceId))
                                .findFirst()
                                .flatMap(row -> settlementSources.byCode(row.code()))
                                .flatMap(com.finapp.settlement.SettlementSourceDescriptor
                                        ::settledPosition)
                                .flatMap(position ->
                                        paymentRails.declaredIds().stream()
                                                .filter(rail ->
                                                        paymentRails.capabilitiesOf(rail)
                                                                .clearingPurpose()
                                                                .equals(java.util.Optional.of(
                                                                        position)))
                                                .findFirst()),
                // The FX covers, for an FX provider's COVER_REF (P9-TSK-011).
                new com.finapp.fx.JdbcTradeStore(),
                // A source's family (P9-TSK-014): its seeded row's code and the compiled
                // descriptor's settled position - every operation key resolved within it.
                (unitOfWork, sourceId) ->
                        settlementFileStore.sources(unitOfWork).stream()
                                .filter(row -> row.id().equals(sourceId))
                                .findFirst()
                                .flatMap(row -> settlementSources.byCode(row.code()))
                                .flatMap(com.finapp.settlement.SettlementSourceDescriptor::settledPosition),
                // The outbound credits, for a corridor source's E (P9-TSK-022).
                new com.finapp.payments.JdbcOutboundCreditStore());
    }

    /**
     * The opening-position backfill (`P8-TSK-007`, ADR-0067 §8): history adopted through the
     * live recorder's own path, page by page, converging on the register's uniques — and
     * since `P8-TSK-009` the accepted batches' remittances re-derived through the live
     * intake's own opener, so the register stays rebuildable from the books alone — and
     * since `P8-TSK-019` every recorded payout return's keyless {@code PAYOUT_RETURN}.
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
            com.finapp.app.settlement.ReconciliationIntake acceptedBatchIntake,
            com.finapp.merchant.PayoutReturnStore<Connection> payoutReturnStore,
            com.finapp.payments.SchemeExecutionClaimStore<Connection> schemeExecutionClaimStore) {
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
                acceptedBatchIntake,
                payoutReturnStore,
                schemeExecutionClaimStore);
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

    /**
     * {@code finapp.reconciliation.rule_set.missing{source}} (`P9-TSK-011`): a declared source with
     * no ACTIVE rule set version - alerted, so a new source's waiting files are never silent.
     */
    @Bean
    com.finapp.app.telemetry.RuleSetMissingMetrics ruleSetMissingMetrics(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            com.finapp.reconciliation.RuleSets ruleSets,
            javax.sql.DataSource dataSource,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new com.finapp.app.telemetry.RuleSetMissingMetrics(
                settlementSources, settlementFileStore, ruleSets, dataSource::getConnection, clock,
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

    /** {@code finapp.reconciliation.replay} (`P8-TSK-022`): the replay's verdict counter. */
    @Bean
    com.finapp.app.telemetry.ReconciliationReplayMeters reconciliationReplayMeters(
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new com.finapp.app.telemetry.ReconciliationReplayMeters(meterRegistry);
    }

    /**
     * Decision replay (`P8-TSK-022`, ADR-0068 §9.1): one repeatable-read snapshot of the run's
     * decisions re-run through the pure functions under their pinned versions, then one short
     * append - the verdict, a divergence's CRITICAL break, the audit record.
     */
    @Bean
    com.finapp.reconciliation.RunReplays runReplays(
            com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner,
            com.finapp.reconciliation.MatchingRules matchingRules,
            com.finapp.reconciliation.MatchingStore matchingStore,
            com.finapp.reconciliation.BreakRegister breakRegister,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.app.telemetry.ReconciliationReplayMeters reconciliationReplayMeters) {
        return new com.finapp.reconciliation.RunReplays(
                reconciliationTransactionRunner,
                new com.finapp.reconciliation.JdbcRunReplayStore(matchingRules),
                matchingStore,
                breakRegister,
                auditWriter,
                idGenerator,
                clock,
                reconciliationReplayMeters);
    }

    /**
     * Rule set administration (`P8-TSK-022`, ADR-0068 §8): a version proposed frozen, activated
     * by a second controller retiring its predecessor in the same transaction, or rejected.
     */
    @Bean
    com.finapp.reconciliation.RuleSetAdministration ruleSetAdministration(
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.RuleSetAdministration(
                new com.finapp.reconciliation.JdbcRuleSetStore(), auditWriter, idGenerator);
    }

    /** A controller's acts on runs (`P8-TSK-022`): reprocessing and requeue. */
    @Bean
    com.finapp.reconciliation.RunAdministration runAdministration(
            com.finapp.reconciliation.MatchingStore matchingStore,
            com.finapp.reconciliation.ReconciliationRuns reconciliationRuns,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        return new com.finapp.reconciliation.RunAdministration(
                matchingStore, reconciliationRuns, auditWriter, idGenerator);
    }

    /** The controller's run doors (`P8-TSK-022`): one transaction per command. */
    @Bean
    ReconciliationAdministrationDesk reconciliationAdministrationDesk(
            com.finapp.reconciliation.RuleSetAdministration ruleSetAdministration,
            com.finapp.reconciliation.RunAdministration runAdministration,
            com.finapp.reconciliation.RunReplays runReplays,
            SettlementFileStore<Connection> settlementFileStore,
            com.finapp.platform.idempotency.IdempotentExecutor idempotentExecutor,
            TransactionTemplate reconciliationTransactions,
            javax.sql.DataSource dataSource,
            Clock clock) {
        return new ReconciliationAdministrationDesk(
                ruleSetAdministration,
                runAdministration,
                runReplays,
                settlementFileStore,
                idempotentExecutor,
                reconciliationTransactions,
                dataSource,
                clock);
    }

    /**
     * The resolutions' evidence writer (`P8-TSK-012`, ADR-0071): the platform's
     * {@code EVIDENCED} kind — born {@code APPROVED} in the transaction whose zero-residual
     * allocation or offset explained the break — and, since `P8-TSK-015`, the withdrawal of a
     * person's pending proposal the evidence overtook, its ledger half rejected through the
     * owned door (evidence wins, §9).
     */
    @Bean
    com.finapp.reconciliation.Resolutions resolutions(
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            com.finapp.ledger.AdjustmentService adjustmentService,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters) {
        return new com.finapp.reconciliation.JdbcResolutions(
                outboxWriter,
                auditWriter,
                idGenerator,
                new com.finapp.reconciliation.JdbcResolutionStore(),
                adjustmentService,
                reconciliationOutcomeMeters);
    }

    /**
     * The person's resolution machine (`P8-TSK-015`, ADR-0071): template-bound kinds under
     * four-eyes, the ledger half through {@code AdjustmentService}'s owned door in the same
     * transaction, every command in the Phase 8 lock order.
     */
    @Bean
    com.finapp.reconciliation.ResolutionMachine resolutionMachine(
            com.finapp.reconciliation.Suspense suspense,
            com.finapp.reconciliation.MatchingStore matchingStore,
            RuleSets ruleSets,
            com.finapp.ledger.AdjustmentService adjustmentService,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters,
            com.finapp.reconciliation.InternalReferenceLookup internalReferenceLookup,
            com.finapp.reconciliation.ReturnedPayouts returnedPayouts,
            com.finapp.reconciliation.ResolvedCorridorReturns resolvedCorridorReturns) {
        return new com.finapp.reconciliation.ResolutionMachine(
                new com.finapp.reconciliation.JdbcResolutionStore(),
                new com.finapp.reconciliation.JdbcBreakCaseStore(),
                suspense,
                matchingStore,
                ruleSets,
                adjustmentService,
                ledgerAccountStore,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock,
                reconciliationOutcomeMeters,
                internalReferenceLookup,
                returnedPayouts,
                resolvedCorridorReturns);
    }

    /**
     * The resolver's desk (`P8-TSK-015`): the four doors' one-transaction commands, and the
     * approver's read in one {@code REPEATABLE READ} snapshot (the Phase 8 -> 9 transition).
     */
    @Bean
    BreakResolutionDesk breakResolutionDesk(
            com.finapp.reconciliation.ResolutionMachine resolutionMachine,
            com.finapp.platform.idempotency.IdempotentExecutor idempotentExecutor,
            TransactionTemplate reconciliationTransactions,
            javax.sql.DataSource dataSource,
            com.finapp.reconciliation.BatchRepudiations batchRepudiations,
            com.finapp.platform.telemetry.Spans domainSpans,
            TransactionTemplate reconciliationSnapshotReads) {
        return new BreakResolutionDesk(
                resolutionMachine, idempotentExecutor, reconciliationTransactions, dataSource,
                batchRepudiations, domainSpans, reconciliationSnapshotReads);
    }

    /**
     * A settlement batch's repudiation (`P8-TSK-023`, ADR-0065 §10): the batch-subject
     * resolution under four-eyes, settlement reached through the composed seam, the
     * recognition reversed through the ledger's {@code ReversalService} - one transaction.
     */
    @Bean
    com.finapp.reconciliation.BatchRepudiations batchRepudiations(
            com.finapp.reconciliation.BreakRegister breakRegister,
            com.finapp.reconciliation.Suspense suspense,
            com.finapp.settlement.BatchRepudiation batchRepudiation,
            com.finapp.ledger.ReversalService reversalService,
            com.finapp.ledger.JournalEntryStore<Connection> journalEntryStore,
            com.finapp.ledger.AdjustmentService adjustmentService,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters) {
        return new com.finapp.reconciliation.BatchRepudiations(
                new com.finapp.reconciliation.JdbcRepudiationStore(),
                new com.finapp.reconciliation.JdbcResolutionStore(),
                new com.finapp.reconciliation.JdbcBreakCaseStore(),
                breakRegister,
                suspense,
                new ComposedBatchRepudiations(batchRepudiation),
                reversalService,
                journalEntryStore,
                adjustmentService,
                ledgerAccountStore,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock,
                reconciliationOutcomeMeters);
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
            com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters,
            SettlementFileStore<Connection> settlementFileStore,
            SettlementSources settlementSources) {
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
                reconciliationTransactionRunner,
                reconciliationOutcomeMeters,
                positionAccounts(ledgerAccountStore, settlementFileStore, settlementSources));
    }

    /**
     * The account a source's position is (`P9-TSK-011`, ADR-0078): read off the composed register -
     * the source row's code, its descriptor, and the counterparty's OWN account when it names one,
     * else the shared operational account. Reconciliation names neither a counterparty nor a source.
     */
    static com.finapp.reconciliation.PositionAccounts positionAccounts(
            com.finapp.ledger.LedgerAccountStore<Connection> accounts,
            SettlementFileStore<Connection> sourceRows,
            SettlementSources sources) {
        return (unitOfWork, sourceId, position, currency) -> {
            java.util.Optional<String> counterparty =
                    sourceRows.sources(unitOfWork).stream()
                            .filter(row -> row.id().equals(sourceId))
                            .findFirst()
                            .flatMap(row -> sources.byCode(row.code()))
                            .flatMap(com.finapp.settlement.SettlementSourceDescriptor::settledCounterparty);
            return (counterparty.isPresent()
                            ? accounts.findCounterpartyAccount(unitOfWork, position, counterparty.get(), currency)
                            : accounts.findOperational(unitOfWork, position, currency))
                    .map(account -> account.id().value());
        };
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
            com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters) {
        return new com.finapp.reconciliation.ReconciliationSweep(
                matchingStore,
                breakRegister,
                keyCollisionBreaks,
                outboxWriter,
                idGenerator,
                clock,
                new com.finapp.reconciliation.ReconciliationSweep.Config(
                        batch, blockAfterFailures),
                reconciliationTransactionRunner,
                reconciliationOutcomeMeters);
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
            Clock clock,
            com.finapp.reconciliation.BreakRegister breakRegister,
            IdGenerator idGenerator,
            com.finapp.app.telemetry.ReconciliationOutcomeMeters reconciliationOutcomeMeters) {
        return new ReconciliationExpectationRecorder(
                settlementSources,
                settlementFileStore,
                journalEntryStore,
                ruleSets,
                expectationRegister,
                clock,
                new com.finapp.reconciliation.ParkedConfirmations(
                        breakRegister, idGenerator, reconciliationOutcomeMeters));
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

    /**
     * The investigation as the break's case file (`P8-TSK-014`, ADR-0069 §7), its assignee judged
     * over identity's public reads (the Phase 8 -> 9 transition, SEC-06).
     */
    @Bean
    com.finapp.reconciliation.BreakCaseFile breakCaseFile(
            ComposedCaseFileEvidence composedCaseFileEvidence,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            com.finapp.identity.Authorization authorization,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            com.finapp.platform.audit.AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.reconciliation.BreakCaseFile(
                new com.finapp.reconciliation.JdbcBreakCaseStore(),
                composedCaseFileEvidence,
                new ComposedInvestigators(identityStore, authorization),
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
