package com.finapp.app.fx;

import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.fx.JdbcRateFetchPermitStore;
import com.finapp.fx.JdbcRateSnapshotStore;
import com.finapp.fx.RateFetchPermitStore;
import com.finapp.fx.RateSnapshotStore;
import com.finapp.fx.RateSource;
import com.finapp.fx.ReferenceRateFetch;
import com.finapp.fx.TransactionRunner;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
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
 * The reference rate's composition (`P9-TSK-005`, ADR-0075 §1): the snapshot and permit stores,
 * the series (eager, whatever is configured), and - only when {@code finapp.fx.reference.url} is
 * set, {@code ProviderTransportGuard} having admitted it - the {@code simulated-reference}
 * adapter under its own confined key, the fetch and its leaderless schedule (off in test
 * contexts, which drive {@link ReferenceRateFetch#fetchOnce()} directly).
 */
@Configuration
public class FxReferenceBeans {

    @Bean
    RateSnapshotStore<Connection> rateSnapshotStore() {
        return new JdbcRateSnapshotStore();
    }

    @Bean
    RateFetchPermitStore<Connection> rateFetchPermitStore() {
        return new JdbcRateFetchPermitStore();
    }

    /**
     * fx's transactions: default isolation (READ COMMITTED - the permit's conditional upsert and
     * the snapshot trigger's post-lock read both rely on a fresh snapshot per statement).
     */
    @Bean
    TransactionRunner fxTransactionRunner(
            PlatformTransactionManager transactionManager, DataSource dataSource) {
        TransactionTemplate fxTransactions = new TransactionTemplate(transactionManager);
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return fxTransactions.execute(
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
    FxRateMetrics fxRateMetrics(
            RateSnapshotStore<Connection> rateSnapshotStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new FxRateMetrics(rateSnapshotStore, dataSource::getConnection, clock, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty("finapp.fx.reference.url")
    RateSource referenceRateSource(
            @Value("${finapp.fx.reference.url}") URI url,
            @Value("${finapp.fx.reference.timeout:PT5S}") Duration timeout,
            @Value("${finapp.fx.reference.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        return new HttpReferenceRateSource(
                url,
                timeout,
                ReferenceRateKey.decode(
                        configuredKey,
                        DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment))));
    }

    @Bean
    @ConditionalOnProperty("finapp.fx.reference.url")
    ReferenceRateFetch referenceRateFetch(
            RateSource referenceRateSource,
            RateSnapshotStore<Connection> rateSnapshotStore,
            RateFetchPermitStore<Connection> rateFetchPermitStore,
            TransactionRunner fxTransactionRunner,
            IdGenerator idGenerator,
            @Value("${finapp.fx.reference.window:PT1M}") Duration window) {
        return new ReferenceRateFetch(
                referenceRateSource,
                rateSnapshotStore,
                rateFetchPermitStore,
                fxTransactionRunner,
                idGenerator,
                window);
    }

    /** The fetch's schedule - leaderless on every instance, off in test contexts. */
    @Bean
    @ConditionalOnProperty("finapp.fx.reference.url")
    @ConditionalOnProperty(
            name = "finapp.fx.rate.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    FxRateFetchSchedule fxRateFetchSchedule(
            ReferenceRateFetch referenceRateFetch,
            FxRateMetrics fxRateMetrics,
            @Value("${finapp.fx.reference.poll:PT15S}") Duration pollInterval) {
        return new FxRateFetchSchedule(referenceRateFetch, fxRateMetrics, pollInterval);
    }

    /** Whether this instance runs the fetch - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge fxRateSweeperEnabled(
            @Value("${finapp.fx.rate.sweeper.enabled:true}") boolean enabled,
            MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.fx.rate.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the reference rate fetch")
                .register(meterRegistry);
    }
}
