package com.finapp.app.settlement;

import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.app.telemetry.CommittedIntakeOutcomes;
import com.finapp.app.telemetry.CommittedReceptionOutcomes;
import com.finapp.app.telemetry.SettlementFileMetrics;
import com.finapp.app.telemetry.SettlementMeters;
import com.finapp.merchant.PayoutSettlementDeclaration;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.RailId;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.EvidenceContentReads;
import com.finapp.settlement.FileAttestation;
import com.finapp.settlement.FileDecline;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.IntakeOutcomeObserver;
import com.finapp.settlement.JdbcSettlementBatchStore;
import com.finapp.settlement.JdbcSettlementFileStore;
import com.finapp.settlement.ReceptionOutcomeObserver;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileCipher;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import com.finapp.settlement.TransactionRunner;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.settlement.format.simpayout.SimPayoutCsvFormat;
import com.finapp.settlement.format.simscheme.SimSchemeJsonFormat;
import com.finapp.settlement.format.simstatement.SimStatementTaggedFormat;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The settlement module's composition (`P8-TSK-002`, ADR-0064, ADR-0066) — and the ONE place
 * the source register is bound, from each counterparty's own declaration ({@code INV-SET-05}):
 * the rails' positions read off {@code RailCapabilities.clearingPurpose()}, the payout's off
 * {@code PayoutSettlementDeclaration.CLEARING_PURPOSE}. This file sits in
 * {@code RailVocabularyIsConfinedTest.CONFIGURATION_FILES}: the composition root may bind
 * declarations; {@code settlement} and {@code reconciliation} may not even name a clearing
 * purpose.
 *
 * <p>No door exists yet (`P8-TSK-003` opens the first), so nothing here maps routes; the
 * beans exist so the register's coverage is verified at every startup and so the door task
 * only adds routes.
 */
@Configuration
public class SettlementBeans {

    /**
     * The register, composed and verified: every declared rail that settles externally has
     * exactly one source discharging its position, and so does the payout — an uncovered
     * settling position refuses composition, so it fails the build and every startup, never a
     * payment (`EverySettlingPositionHasASourceTest` drives both ways).
     */
    static SettlementSources composedSettlementSources(PaymentRails rails) {
        SettlementSources sources =
                SettlementSources.of(
                        List.of(
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        rails.capabilitiesOf(
                                                        com.finapp.payments.SimulatedCardPspAdapter
                                                                .RAIL.id())
                                                .clearingPurpose(),
                                        Optional.of("PSP-REM-[0-9]{4,12}")),
                                new SettlementSourceDescriptor(
                                        "simulated-scheme.cycle-report",
                                        SourceKind.SCHEME_CYCLE_REPORT,
                                        SettlementFormatId.SIM_SCHEME_JSON,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        rails.capabilitiesOf(
                                                        com.finapp.payments
                                                                .SimulatedInstantSchemeAdapter
                                                                .RAIL.id())
                                                .clearingPurpose(),
                                        Optional.of(SimSchemeJsonFormat.REMITTANCE_REFERENCE)),
                                new SettlementSourceDescriptor(
                                        "simulated-payout.settlement",
                                        SourceKind.PAYOUT_PROVIDER_REPORT,
                                        SettlementFormatId.SIM_PAYOUT_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.of(PayoutSettlementDeclaration.CLEARING_PURPOSE),
                                        Optional.of(SimPayoutCsvFormat.REMITTANCE_REFERENCE)),
                                new SettlementSourceDescriptor(
                                        "simulated-bank.statement",
                                        SourceKind.BANK_STATEMENT,
                                        SettlementFormatId.SIM_STATEMENT_TAGGED,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.empty(),
                                        Optional.empty())));
        for (RailId rail : rails.declaredIds()) {
            rails.capabilitiesOf(rail)
                    .clearingPurpose()
                    .ifPresent(
                            position -> {
                                if (sources.dischargedBy(position).isEmpty()) {
                                    throw new IllegalStateException(
                                            "rail '" + rail.value() + "' settles externally on "
                                                    + position + " and no settlement source"
                                                    + " discharges it: every externally settling"
                                                    + " position has exactly one declared source"
                                                    + " (INV-SET-05)");
                                }
                            });
        }
        if (sources.dischargedBy(PayoutSettlementDeclaration.CLEARING_PURPOSE).isEmpty()) {
            throw new IllegalStateException(
                    "the payout settles externally on "
                            + PayoutSettlementDeclaration.CLEARING_PURPOSE
                            + " and no settlement source discharges it (INV-SET-05)");
        }
        return sources;
    }

    @Bean
    SettlementSources settlementSources(PaymentRails paymentRails) {
        return composedSettlementSources(paymentRails);
    }

    /** Nonces for the file cipher — its own instance, the payments randomness precedent. */
    @Bean
    SecureRandom settlementRandomness() {
        return new SecureRandom();
    }

    @Bean
    SettlementFileStore<Connection> settlementFileStore(
            @Value("${finapp.settlement.file.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT
                            + "}")
                    String configuredKey,
            @Value("${finapp.settlement.file.key-version:1}") int keyVersion,
            SecureRandom settlementRandomness,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new JdbcSettlementFileStore(
                new SettlementFileCipher(
                        SettlementFileKey.decode(configuredKey, loopback),
                        keyVersion,
                        settlementRandomness));
    }

    @Bean
    SettlementMeters settlementMeters(MeterRegistry meterRegistry, SettlementSources settlementSources) {
        return new SettlementMeters(meterRegistry, settlementSources);
    }

    @Bean
    ReceptionOutcomeObserver receptionOutcomeObserver(SettlementMeters settlementMeters) {
        return new CommittedReceptionOutcomes(settlementMeters);
    }

    /**
     * The compiled formats, by family (`P8-TSK-008`, ADR-0066 §8): the one map the door's
     * field-class screens and the parse leg both read, so a source cannot be screened under
     * one version and parsed under another. `SIM_PSP_CSV` v1 is the first; `-016`…`-018`
     * add theirs.
     *
     * <p>`SIM_STATEMENT_TAGGED` v1 (`P8-TSK-016`) is constructed with the bank's OPAQUE
     * reference of the platform's settlement account per currency — deployment configuration,
     * classified CONFIDENTIAL, never logged: a statement is ours only when its account record
     * equals the reference configured for its currency ({@code INV-RAIL-03}). The defaults are
     * the simulated bank's. `SIM_SCHEME_JSON` v1 (`P8-TSK-017`) is the instant scheme's cycle
     * report and `SIM_PAYOUT_CSV` v1 (`P8-TSK-018`) the payout provider's daily report — both
     * pure, singletons like the PSP's.
     */
    @Bean
    Map<SettlementFormatId, SettlementFormat> settlementFormats(
            @Value("${finapp.settlement.bank.account-reference.EUR:SIMBANK-EUR-01}")
                    String eurAccountReference,
            @Value("${finapp.settlement.bank.account-reference.GBP:SIMBANK-GBP-01}")
                    String gbpAccountReference,
            @Value("${finapp.settlement.bank.account-reference.USD:SIMBANK-USD-01}")
                    String usdAccountReference) {
        return Map.of(
                SettlementFormatId.SIM_PSP_CSV,
                SimPspCsvFormat.INSTANCE,
                SettlementFormatId.SIM_SCHEME_JSON,
                SimSchemeJsonFormat.INSTANCE,
                SettlementFormatId.SIM_PAYOUT_CSV,
                SimPayoutCsvFormat.INSTANCE,
                SettlementFormatId.SIM_STATEMENT_TAGGED,
                new SimStatementTaggedFormat(
                        Map.of(
                                CurrencyCode.of("EUR"), eurAccountReference,
                                CurrencyCode.of("GBP"), gbpAccountReference,
                                CurrencyCode.of("USD"), usdAccountReference)));
    }

    /**
     * The door itself — unconditional: it calls nothing outside the database. The screens
     * map was `P8-TSK-008`'s seam and is now filled per compiled format version; a source
     * whose format has none keeps the conservative whole-stream screen (ADR-0066 §3).
     */
    @Bean
    FileReception<Connection> fileReception(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            Map<SettlementFormatId, SettlementFormat> settlementFormats,
            ReceptionOutcomeObserver receptionOutcomeObserver,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        Map<SettlementFormatId, DeliveryScreen> screens =
                settlementFormats.entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> entry.getValue()::screen));
        return new FileReception<>(
                settlementSources,
                settlementFileStore,
                screens,
                receptionOutcomeObserver,
                auditWriter,
                idGenerator,
                clock);
    }

    /**
     * The settlement transaction (`P8-TSK-003`): {@code REQUIRES_NEW} and default isolation —
     * every contended decision inside is a conditional {@code UPDATE}'s row count or a
     * unique constraint (the {@code paymentTransactions} recorded reasons).
     */
    @Bean
    TransactionTemplate settlementTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    /** The second person's act (`INV-SET-07`): row lock, conditional, audit — one transaction. */
    @Bean
    FileAttestation<Connection> fileAttestation(
            SettlementFileStore<Connection> settlementFileStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new FileAttestation<>(settlementFileStore, auditWriter, idGenerator, clock);
    }

    /** The one content path (`INV-REC-10`): verified, reasoned, audited per read. */
    @Bean
    EvidenceContentReads<Connection> evidenceContentReads(
            SettlementFileStore<Connection> settlementFileStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new EvidenceContentReads<>(settlementFileStore, auditWriter, idGenerator, clock);
    }

    // ------------------------------------------------------------------
    // The parse leg and the decline (P8-TSK-008).
    // ------------------------------------------------------------------

    @Bean
    SettlementBatchStore<Connection> settlementBatchStore(IdGenerator idGenerator) {
        return new JdbcSettlementBatchStore(idGenerator);
    }

    /** One transaction per call — the parse leg's per-file containment (ADR-0066 §9). */
    @Bean
    TransactionRunner settlementTransactionRunner(
            TransactionTemplate settlementTransactions, DataSource dataSource) {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return settlementTransactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return work.apply(unitOfWork);
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        });
            }
        };
    }

    @Bean
    IntakeOutcomeObserver intakeOutcomeObserver(SettlementMeters settlementMeters) {
        return new CommittedIntakeOutcomes(settlementMeters);
    }

    @Bean
    FileParsing fileParsing(
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            Map<SettlementFormatId, SettlementFormat> settlementFormats,
            @Value("${finapp.settlement.intake.batch:10}") int filesPerSweep,
            @Value("${finapp.settlement.intake.backoff-base:PT1M}") Duration backoffBase,
            @Value("${finapp.settlement.intake.backoff-cap:PT1H}") Duration backoffCap,
            IntakeOutcomeObserver intakeOutcomeObserver,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionRunner settlementTransactionRunner,
            SettlementSources settlementSources) {
        return new FileParsing(
                settlementFileStore,
                settlementBatchStore,
                settlementFormats,
                new FileParsing.Config(filesPerSweep, backoffBase, backoffCap),
                intakeOutcomeObserver,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock,
                settlementTransactionRunner,
                settlementSources);
    }

    /**
     * The seam through which an accepted batch becomes reconciliation's work
     * (`P8-TSK-009`, ADR-0064 §6): settlement declares the port, this composition joins it
     * to reconciliation's writers and the ledger's chart on the acceptance's connection.
     * Declared by its concrete type so the opening-position backfill can share the
     * remittance opener (ADR-0067 §8); settlement still sees only the port.
     */
    @Bean
    ReconciliationIntake acceptedBatchIntake(
            com.finapp.reconciliation.ReconciliationRuns reconciliationRuns,
            com.finapp.reconciliation.ExternalItems externalItems,
            com.finapp.reconciliation.ExpectationRegister expectationRegister,
            com.finapp.reconciliation.RuleSets ruleSets,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            IdGenerator idGenerator,
            com.finapp.reconciliation.BreakRegister breakRegister,
            com.finapp.reconciliation.Suspense suspense,
            com.finapp.reconciliation.StatementChain statementChain) {
        return new ReconciliationIntake(
                reconciliationRuns,
                externalItems,
                expectationRegister,
                ruleSets,
                ledgerAccountStore,
                idGenerator,
                breakRegister,
                suspense,
                statementChain);
    }

    /** The accept leg (`P8-TSK-009`): hop 1, once per batch, one transaction per file. */
    @Bean
    com.finapp.settlement.BatchAcceptance batchAcceptance(
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            SettlementSources settlementSources,
            com.finapp.settlement.AcceptedBatchIntake acceptedBatchIntake,
            com.finapp.ledger.PostingService postingService,
            com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccountStore,
            @Value("${finapp.settlement.accept.batch:10}") int filesPerSweep,
            IntakeOutcomeObserver intakeOutcomeObserver,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionRunner settlementTransactionRunner) {
        return new com.finapp.settlement.BatchAcceptance(
                settlementFileStore,
                settlementBatchStore,
                settlementSources,
                acceptedBatchIntake,
                postingService,
                ledgerAccountStore,
                new com.finapp.settlement.BatchAcceptance.Config(filesPerSweep),
                intakeOutcomeObserver,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock,
                settlementTransactionRunner);
    }

    /**
     * The intake's schedule — both legs, leaderless on every instance, off in test contexts
     * (the relay's flag discipline); registered in {@code DISTRIBUTED_EXECUTION.md} §3.
     */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.settlement.intake.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    SettlementIntakeSchedule settlementIntakeSchedule(
            FileParsing fileParsing,
            com.finapp.settlement.BatchAcceptance batchAcceptance,
            @Value("${finapp.settlement.intake.poll:PT15S}") Duration pollInterval) {
        return new SettlementIntakeSchedule(fileParsing, batchAcceptance, pollInterval);
    }

    /**
     * Whether this instance runs the intake sweeper — eager either way, so "off" reads as
     * {@code 0} rather than as a missing series (`P1-TSK-029`'s rule).
     */
    @Bean
    io.micrometer.core.instrument.Gauge settlementIntakeSweeperEnabled(
            @Value("${finapp.settlement.intake.sweeper.enabled:true}") boolean enabled,
            MeterRegistry meterRegistry) {
        return io.micrometer.core.instrument.Gauge.builder(
                        "finapp.settlement.intake.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the settlement intake sweeper")
                .register(meterRegistry);
    }

    @Bean
    FileDecline fileDecline(
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            IntakeOutcomeObserver intakeOutcomeObserver,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new FileDecline(
                settlementFileStore,
                settlementBatchStore,
                intakeOutcomeObserver,
                outboxWriter,
                auditWriter,
                idGenerator,
                clock);
    }

    @Bean
    SettlementOperations settlementOperations(
            SettlementSources settlementSources,
            FileReception<Connection> fileReception,
            FileAttestation<Connection> fileAttestation,
            EvidenceContentReads<Connection> evidenceContentReads,
            SettlementFileStore<Connection> settlementFileStore,
            FileDecline fileDecline,
            SettlementBatchStore<Connection> settlementBatchStore,
            IdempotentExecutor idempotentExecutor,
            TransactionTemplate settlementTransactions,
            DataSource dataSource,
            com.finapp.settlement.FileReadmission fileReadmission,
            com.finapp.settlement.FileVerification fileVerification,
            Clock clock) {
        return new SettlementOperations(
                settlementSources,
                fileReception,
                fileAttestation,
                evidenceContentReads,
                settlementFileStore,
                fileDecline,
                settlementBatchStore,
                idempotentExecutor,
                settlementTransactions,
                dataSource,
                fileReadmission,
                fileVerification,
                clock);
    }

    /**
     * Readmission (`P8-TSK-022`, ADR-0066 §8): the original's verified bytes screened under the
     * current format, re-stored under a NEW file naming it; one SQL function decides whether it
     * inherits authentication, for the trigger and the accept leg alike.
     */
    @Bean
    com.finapp.settlement.FileReadmission fileReadmission(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            Map<SettlementFormatId, SettlementFormat> settlementFormats,
            ReceptionOutcomeObserver receptionOutcomeObserver,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        Map<SettlementFormatId, DeliveryScreen> screens =
                settlementFormats.entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> entry.getValue()::screen));
        return new com.finapp.settlement.FileReadmission(
                settlementSources,
                settlementFileStore,
                settlementBatchStore,
                settlementFormats,
                screens,
                receptionOutcomeObserver,
                auditWriter,
                idGenerator);
    }

    /** The re-parse verification (`P8-TSK-022`, ADR-0066 §9): reasoned, audited, read-only. */
    @Bean
    com.finapp.settlement.FileVerification fileVerification(
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            Map<SettlementFormatId, SettlementFormat> settlementFormats,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        return new com.finapp.settlement.FileVerification(
                settlementFileStore, settlementBatchStore, settlementFormats, auditWriter,
                idGenerator);
    }

    /**
     * The file gauges (`P8-TSK-003`, `PHASE_8_PLAN.md` §15): pending and oldest-age per
     * source, over the application's own {@code DataSource} — the {@code TelemetryConfiguration}
     * siblings' reasoning, declared here because the register and store are this module's.
     */
    @Bean
    SettlementFileMetrics settlementFileMetrics(
            SettlementFileStore<Connection> settlementFileStore,
            SettlementSources settlementSources,
            DataSource dataSource,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new SettlementFileMetrics(
                settlementFileStore,
                settlementSources,
                dataSource::getConnection,
                clock,
                meterRegistry);
    }
}
