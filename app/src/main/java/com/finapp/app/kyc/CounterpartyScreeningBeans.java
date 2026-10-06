package com.finapp.app.kyc;

import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.kyc.CounterpartyScreeningObserver;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningStore;
import com.finapp.kyc.CounterpartyScreeningVocabulary;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.kyc.CounterpartySubjectCipher;
import com.finapp.kyc.JdbcCounterpartyScreeningStore;
import com.finapp.kyc.ScreeningAdapter;
import com.finapp.kyc.ScreeningOutcomeListener;
import com.finapp.kyc.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
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
 * Wiring for counterparty screening (`P9-TSK-016`, ADR-0081): kyc's own transaction runner, the subject
 * cipher under kyc's evidence key, the store, the provider over the existing screening endpoint and
 * credential, the meters, the review desk and the leaderless retry schedule. The listener is the
 * cross-border beneficiary's, composed in {@code CrossBorderBeneficiaryBeans} (`P9-TSK-017`).
 */
@Configuration(proxyBeanMethods = false)
public class CounterpartyScreeningBeans {

    /** kyc's transaction runner - the {@code crossborderTransactionRunner} shape, default propagation. */
    @Bean
    TransactionRunner kycTransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource) {
        TransactionTemplate kycRunnerTransactions = new TransactionTemplate(transactionManager);
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return kycRunnerTransactions.execute(
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

    /**
     * The subject cipher - kyc's evidence key ({@code FINAPP_DOC_KEY}, ADR-0036's one at-rest concern
     * for kyc's restricted bytes), read through the same confinement as {@code documentCipher}.
     */
    @Bean
    CounterpartySubjectCipher counterpartySubjectCipher(
            @Value("${finapp.doc.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}") String configuredKey,
            @Value("${finapp.doc.key-version:1}") int keyVersion,
            Environment environment,
            SecureRandom kycRandomness) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new CounterpartySubjectCipher(DocumentKey.decode(configuredKey, loopback), keyVersion, kycRandomness);
    }

    @Bean
    CounterpartyScreeningStore counterpartyScreeningStore() {
        return new JdbcCounterpartyScreeningStore();
    }

    /**
     * The counterparty provider - the existing sanctions screening endpoint and credential (ADR-0081
     * point 1), as a port of its own so it never joins the case-bound providers list. With no endpoint
     * configured, every screening is {@code UNAVAILABLE}: fail safe, nothing cleared, nothing held.
     */
    @Bean
    CounterpartyScreeningProvider counterpartyScreeningProvider(
            @Value("${finapp.kyc.provider.url:}") String providerUrl,
            @Value("${finapp.kyc.provider.timeout:PT2S}") Duration timeout) {
        if (providerUrl.isBlank()) {
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.withoutEvidence(
                    CounterpartyScreeningVocabulary.Verdict.UNAVAILABLE);
        }
        ScreeningAdapter adapter = ScreeningAdapter.sanctions(URI.create(providerUrl), timeout);
        return adapter::screen;
    }

    @Bean
    CounterpartyScreeningMetrics counterpartyScreeningMetrics(
            CounterpartyScreeningStore counterpartyScreeningStore,
            TransactionRunner kycTransactionRunner,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new CounterpartyScreeningMetrics(
                () -> kycTransactionRunner.inTransaction(counterpartyScreeningStore::reviewBacklog), clock, meterRegistry);
    }

    @Bean
    CounterpartyScreenings counterpartyScreenings(
            CounterpartyScreeningStore counterpartyScreeningStore,
            CounterpartySubjectCipher counterpartySubjectCipher,
            CounterpartyScreeningProvider counterpartyScreeningProvider,
            ScreeningOutcomeListener screeningOutcomeListener,
            CounterpartyScreeningMetrics counterpartyScreeningMetrics,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            TransactionRunner kycTransactionRunner,
            IdGenerator idGenerator,
            Clock clock) {
        CounterpartyScreeningObserver observer = counterpartyScreeningMetrics;
        return new CounterpartyScreenings(
                counterpartyScreeningStore, counterpartySubjectCipher, counterpartyScreeningProvider, screeningOutcomeListener,
                observer, auditWriter, outboxWriter, kycTransactionRunner, idGenerator, clock);
    }

    @Bean
    CounterpartyScreeningDesk counterpartyScreeningDesk(
            CounterpartyScreenings counterpartyScreenings,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner kycTransactionRunner) {
        return new CounterpartyScreeningDesk(counterpartyScreenings, idempotentExecutor, kycTransactionRunner);
    }

    /** The retry sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.kyc.counterparty.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    CounterpartyScreeningRetrySchedule counterpartyScreeningRetrySchedule(
            CounterpartyScreenings counterpartyScreenings,
            IdGenerator idGenerator,
            @Value("${finapp.kyc.counterparty.poll:PT30S}") Duration pollInterval,
            @Value("${finapp.kyc.counterparty.batch:20}") int batch) {
        return new CounterpartyScreeningRetrySchedule(counterpartyScreenings, idGenerator, pollInterval, batch);
    }

    /** Whether this instance runs the retry sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge counterpartyScreeningSweeperEnabled(
            @Value("${finapp.kyc.counterparty.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.kyc.counterparty.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the counterparty screening retry sweep")
                .register(meterRegistry);
    }
}
