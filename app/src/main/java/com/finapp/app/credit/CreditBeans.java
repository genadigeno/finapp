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
}
