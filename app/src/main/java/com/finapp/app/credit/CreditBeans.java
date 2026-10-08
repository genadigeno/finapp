package com.finapp.app.credit;

import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.consent.ConsentGate;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditConsentGate;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataRequestStore;
import com.finapp.credit.CreditEvidenceCipher;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.JdbcCreditPolicyStore;
import com.finapp.credit.JdbcCreditDataRequestStore;
import com.finapp.credit.JdbcScorecardStore;
import com.finapp.credit.ScorecardAdministration;
import com.finapp.credit.ScorecardStore;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for credit data collection (`P10-TSK-006`; the financial-data source since `P10-TSK-007`): credit's own transaction runner, the consent gate's adapter, the
 * evidence cipher under credit's own key, the store, the bureau, the meters, the collection and the leaderless retry
 * schedule; and (`P10-TSK-011`) the scorecard administration behind its doors - its first consumer.
 *
 * <p><strong>The bureau is fail-safe until party facts exist.</strong> With no {@code finapp.credit.bureau.url}, the
 * bureau is {@link UnconfiguredBureau} - every pull {@code Unavailable}, nothing ever data. A configured URL is refused
 * at startup: the bureau matches a person by name, date of birth and residence, and the platform holds no date of
 * birth or residence for a party yet ({@code CURRENT_STATE.md} unresolved question #13) - a pull that guessed would be
 * worse than none.
 */
@Configuration(proxyBeanMethods = false)
public class CreditBeans {

    /** credit's transaction runner - the {@code kycTransactionRunner} shape, default propagation. */
    @Bean
    TransactionRunner creditTransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource) {
        TransactionTemplate creditRunnerTransactions = new TransactionTemplate(transactionManager);
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return creditRunnerTransactions.execute(status -> {
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

    /** The consent gate's credit adapter - its first consumer arrives here (`P10-TSK-002`'s deferred wiring). */
    @Bean
    CreditConsentGate<Connection> creditConsentGate(ConsentGate<Connection> consentGate) {
        return new ConsentBackedCreditConsentGate(consentGate);
    }

    @Bean
    CreditEvidenceCipher creditEvidenceCipher(
            @Value("${finapp.credit.evidence.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}") String configuredKey,
            @Value("${finapp.credit.evidence.key-version:1}") int keyVersion,
            Environment environment) {
        return new CreditEvidenceCipher(
                CreditEvidenceKey.decode(
                        configuredKey, DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment))),
                keyVersion,
                new SecureRandom());
    }

    @Bean
    CreditDataRequestStore creditDataRequestStore() {
        return new JdbcCreditDataRequestStore();
    }

    /** The bureau - fail-safe when unconfigured; a configured one is refused until party facts exist (#13). */
    @Bean
    CreditBureau creditBureau(@Value("${finapp.credit.bureau.url:}") String bureauUrl) {
        if (!bureauUrl.isBlank()) {
            throw new IllegalStateException("finapp.credit.bureau.url is set, but a bureau pull needs the party's date of"
                    + " birth and residence, which the platform does not yet hold (unresolved question #13)");
        }
        return new UnconfiguredBureau();
    }

    /**
     * The financial-data provider (`P10-TSK-007`) - fail-safe when unconfigured; a configured one is refused until the
     * account connection it reads exists (#14).
     */
    @Bean
    com.finapp.credit.FinancialDataProvider financialDataProvider(@Value("${finapp.credit.findata.url:}") String url) {
        if (!url.isBlank()) {
            throw new IllegalStateException("finapp.credit.findata.url is set, but a financial-data pull reads an account"
                    + " connection the platform does not yet hold (unresolved question #14)");
        }
        return new UnconfiguredFinancialData();
    }

    @Bean
    CreditDataMetrics creditDataMetrics(MeterRegistry meterRegistry) {
        return new CreditDataMetrics(meterRegistry);
    }

    @Bean
    CreditDataCollection creditDataCollection(
            CreditDataRequestStore creditDataRequestStore,
            CreditBureau creditBureau,
            com.finapp.credit.FinancialDataProvider financialDataProvider,
            CreditConsentGate<Connection> creditConsentGate,
            CreditEvidenceCipher creditEvidenceCipher,
            CreditDataMetrics creditDataMetrics,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            TransactionRunner creditTransactionRunner,
            IdGenerator idGenerator,
            Clock clock,
            @Value("${finapp.credit.bureau.retry-cadence:PT1M}") Duration retryCadence,
            @Value("${finapp.credit.bureau.collection-window:PT30M}") Duration collectionWindow,
            @Value("${finapp.credit.findata.retry-cadence:PT1M}") Duration findataRetryCadence,
            @Value("${finapp.credit.findata.collection-window:PT30M}") Duration findataCollectionWindow) {
        return new CreditDataCollection(creditDataRequestStore,
                CreditDataCollection.Sources.of(
                        creditBureau, new CreditDataCollection.Timing(retryCadence, collectionWindow),
                        financialDataProvider, new CreditDataCollection.Timing(findataRetryCadence, findataCollectionWindow)),
                creditConsentGate, creditEvidenceCipher, creditDataMetrics, auditWriter, outboxWriter,
                creditTransactionRunner, idGenerator, clock);
    }

    /** The retry sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.credit.data.retry.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    CreditDataRetrySchedule creditDataRetrySchedule(
            CreditDataCollection creditDataCollection,
            IdGenerator idGenerator,
            @Value("${finapp.credit.data.retry.poll:PT30S}") Duration pollInterval,
            @Value("${finapp.credit.data.retry.batch:20}") int batch) {
        return new CreditDataRetrySchedule(creditDataCollection, idGenerator, pollInterval, batch);
    }

    /** Whether this instance runs the retry sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge creditDataRetrySweeperEnabled(
            @Value("${finapp.credit.data.retry.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.credit.data.retry.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the credit data retry sweep")
                .register(meterRegistry);
    }

    @Bean
    ScorecardStore scorecardStore() {
        return new JdbcScorecardStore();
    }

    /** The scorecard model's four-eyes administration (`P10-TSK-011`). */
    @Bean
    ScorecardAdministration scorecardAdministration(
            ScorecardStore scorecardStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new ScorecardAdministration(scorecardStore, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    ScorecardAdministrationDesk scorecardAdministrationDesk(
            ScorecardAdministration scorecardAdministration,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner creditTransactionRunner) {
        return new ScorecardAdministrationDesk(scorecardAdministration, idempotentExecutor, creditTransactionRunner);
    }

    @Bean
    CreditPolicyStore creditPolicyStore() {
        return new JdbcCreditPolicyStore();
    }

    /** The credit policy's four-eyes administration (`P10-TSK-012`). */
    @Bean
    CreditPolicyAdministration creditPolicyAdministration(
            CreditPolicyStore creditPolicyStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new CreditPolicyAdministration(creditPolicyStore, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    CreditPolicyDesk creditPolicyDesk(
            CreditPolicyAdministration creditPolicyAdministration,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner creditTransactionRunner) {
        return new CreditPolicyDesk(creditPolicyAdministration, idempotentExecutor, creditTransactionRunner);
    }

    /**
     * {@code finapp.credit.policy.active{product}} (`P10-TSK-012`): each offered product's ACTIVE policy version, 0 for
     * none - alerted, so a product nobody can be decided for is never silent.
     */
    @Bean
    com.finapp.app.telemetry.CreditPolicyMetrics creditPolicyMetrics(
            CreditPolicyStore creditPolicyStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new com.finapp.app.telemetry.CreditPolicyMetrics(
                creditPolicyStore, dataSource::getConnection, clock, meterRegistry);
    }
    // ------------------------------------------------------------------ the decision request (P10-TSK-014)

    /** The party's credit standing and facts over the party store - a verified customer, ACTIVE (`P10-TSK-014`). */
    @Bean
    com.finapp.credit.CreditPartyStanding<Connection> creditPartyStanding(
            com.finapp.party.PartyStore<Connection> partyStore) {
        return new PartyCreditStanding(partyStore);
    }

    @Bean
    com.finapp.credit.DecisionRequests decisionRequests(
            CreditPolicyStore creditPolicyStore,
            com.finapp.credit.CreditPartyStanding<Connection> creditPartyStanding,
            CreditConsentGate<Connection> creditConsentGate,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.credit.DecisionRequests(new com.finapp.credit.JdbcDecisionRequestStore(),
                new com.finapp.credit.JdbcCreditProfiles(idGenerator), creditPolicyStore, creditPartyStanding, creditConsentGate,
                outboxWriter, auditWriter, idGenerator, clock);
    }

    @Bean
    CreditDecisionRequestDesk creditDecisionRequestDesk(
            com.finapp.credit.DecisionRequests decisionRequests,
            com.finapp.identity.IdentityStore<Connection> identityStore,
            com.finapp.identity.MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner creditTransactionRunner) {
        return new CreditDecisionRequestDesk(decisionRequests, identityStore, mfaEnrolmentStore, idempotentExecutor,
                creditTransactionRunner, new com.finapp.credit.JdbcCreditDecisions(), new com.finapp.credit.JdbcCreditReads());
    }
    // ------------------------------------------------------------------ the progress (P10-TSK-015)

    @Bean
    com.finapp.credit.SnapshotFreezer snapshotFreezer(
            CreditDataCollection creditDataCollection,
            com.finapp.credit.CreditPartyStanding<Connection> creditPartyStanding,
            IdGenerator idGenerator) {
        return new com.finapp.credit.SnapshotFreezer(new com.finapp.credit.JdbcDecisionSnapshotStore(), creditDataCollection,
                creditPartyStanding, new NotAssessedUntilPhase13(), new com.finapp.credit.JdbcReservedExposure(),
                new NoLoansUntilPhase11(), idGenerator);
    }

    /** The decision's meters, counted after each commit (`P10-TSK-016`). */
    @Bean
    CreditDecisionMetrics creditDecisionMetrics(MeterRegistry meterRegistry) {
        return new CreditDecisionMetrics(meterRegistry);
    }

    /** The deciding transaction, profile-first (`P10-TSK-016`). */
    @Bean
    com.finapp.credit.DecisionMaking decisionMaking(
            TransactionRunner creditTransactionRunner,
            CreditPolicyStore creditPolicyStore,
            ScorecardStore scorecardStore,
            com.finapp.credit.SnapshotFreezer snapshotFreezer,
            com.finapp.credit.CreditPartyStanding<Connection> creditPartyStanding,
            CreditConsentGate<Connection> creditConsentGate,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            CreditDecisionMetrics creditDecisionMetrics,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.credit.DecisionMaking(creditTransactionRunner, new com.finapp.credit.JdbcDecisionRequestStore(),
                new com.finapp.credit.JdbcCreditProfiles(idGenerator), creditPolicyStore, scorecardStore, snapshotFreezer,
                new com.finapp.credit.CreditAssessments(new com.finapp.credit.JdbcCreditAssessmentStore(), scorecardStore,
                        outboxWriter, idGenerator, clock),
                new com.finapp.credit.JdbcCreditAssessmentStore(),
                new com.finapp.credit.PolicyEvaluations(new com.finapp.credit.JdbcPolicyEvaluationStore(), creditPolicyStore,
                        com.finapp.credit.EngineVersions.STANDARD, idGenerator, clock),
                new com.finapp.credit.JdbcPolicyEvaluationStore(), new com.finapp.credit.JdbcCreditDecisions(),
                creditPartyStanding, creditConsentGate, outboxWriter, auditWriter, creditDecisionMetrics, idGenerator, clock);
    }

    /** The published decision read (`P10-TSK-016`) - Phase 11's lending's; no consumer in Phase 10. */
    @Bean
    com.finapp.credit.CreditDecisions<Connection> creditDecisions() {
        return new com.finapp.credit.JdbcCreditDecisions();
    }

    @Bean
    com.finapp.credit.DecisionProgress decisionProgress(
            TransactionRunner creditTransactionRunner,
            CreditPolicyStore creditPolicyStore,
            ScorecardStore scorecardStore,
            CreditDataCollection creditDataCollection,
            com.finapp.credit.SnapshotFreezer snapshotFreezer,
            com.finapp.credit.CreditPartyStanding<Connection> creditPartyStanding,
            CreditConsentGate<Connection> creditConsentGate,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.credit.DecisionMaking decisionMaking) {
        return new com.finapp.credit.DecisionProgress(creditTransactionRunner, new com.finapp.credit.JdbcDecisionRequestStore(),
                creditPolicyStore, scorecardStore, new com.finapp.credit.JdbcDecisionSnapshotStore(), creditDataCollection,
                snapshotFreezer,
                new com.finapp.credit.CreditAssessments(new com.finapp.credit.JdbcCreditAssessmentStore(), scorecardStore,
                        outboxWriter, idGenerator, clock),
                new com.finapp.credit.PolicyEvaluations(new com.finapp.credit.JdbcPolicyEvaluationStore(), creditPolicyStore,
                        com.finapp.credit.EngineVersions.STANDARD, idGenerator, clock),
                creditPartyStanding, creditConsentGate, outboxWriter, idGenerator, clock, decisionMaking);
    }

    /** The progress sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.credit.progress.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    CreditDecisionProgressSchedule creditDecisionProgressSchedule(
            com.finapp.credit.DecisionProgress decisionProgress,
            IdGenerator idGenerator,
            @Value("${finapp.credit.progress.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.credit.progress.batch:20}") int batch,
            @Value("${finapp.credit.progress.permit:PT30S}") Duration permit) {
        return new CreditDecisionProgressSchedule(decisionProgress, idGenerator, pollInterval, batch, permit);
    }

    /** Whether this instance runs the progress sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge creditProgressSweeperEnabled(
            @Value("${finapp.credit.progress.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.credit.progress.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the credit decision progress sweep")
                .register(meterRegistry);
    }

    // ------------------------------------------------------------------ the investigator (P10-TSK-017)

    @Bean
    CreditInvestigationDesk creditInvestigationDesk(
            CreditEvidenceCipher creditEvidenceCipher,
            CreditPolicyStore creditPolicyStore,
            ScorecardStore scorecardStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionRunner creditTransactionRunner) {
        return new CreditInvestigationDesk(new com.finapp.credit.CreditInvestigations(new com.finapp.credit.JdbcCreditDecisions(),
                new com.finapp.credit.JdbcDecisionSnapshotStore(), new com.finapp.credit.JdbcCreditAssessmentStore(),
                new com.finapp.credit.JdbcPolicyEvaluationStore(), creditPolicyStore, scorecardStore,
                new com.finapp.credit.JdbcCreditReads(), creditEvidenceCipher, auditWriter, idGenerator, clock),
                creditTransactionRunner);
    }
}
