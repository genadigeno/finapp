package com.finapp.app.fx;

import com.finapp.fx.ConversionParticipants;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.FxProviders;
import com.finapp.fx.JdbcQuoteStore;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.QuoteIssuance;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.RateSnapshotStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The FX quote's composition (`P9-TSK-008`): the store, the issuance and lifecycle services, the
 * customer's desk, the metrics, and the leaderless expiry schedule (off in test contexts). The
 * {@link ConversionParticipants} the issuance asks is composed by {@code FxConversionBeans}.
 */
@Configuration
public class FxQuoteBeans {

    @Bean
    QuoteStore quoteStore(IdGenerator idGenerator) {
        return new JdbcQuoteStore(idGenerator);
    }

    @Bean
    QuoteIssuance quoteIssuance(
            PricingPolicyStore pricingPolicyStore,
            FxAvailability fxAvailability,
            QuoteStore quoteStore,
            RateSnapshotStore<Connection> rateSnapshotStore,
            FxProviders fxProviders,
            FxProviderEvidenceStore<Connection> fxProviderEvidenceStore,
            ConversionParticipants conversionParticipants,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new QuoteIssuance(
                pricingPolicyStore, fxAvailability, quoteStore, rateSnapshotStore, fxProviders,
                fxProviderEvidenceStore, conversionParticipants, outboxWriter, idGenerator, clock);
    }

    @Bean
    QuoteLifecycle quoteLifecycle(
            QuoteStore quoteStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new QuoteLifecycle(quoteStore, auditWriter, outboxWriter, idGenerator);
    }

    @Bean
    FxQuoteMetrics fxQuoteMetrics(QuoteStore quoteStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new FxQuoteMetrics(quoteStore, dataSource::getConnection, clock, meterRegistry);
    }

    @Bean
    FxQuoteDesk fxQuoteDesk(
            QuoteIssuance quoteIssuance,
            QuoteLifecycle quoteLifecycle,
            PricingPolicyStore pricingPolicyStore,
            FxAvailability fxAvailability,
            IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner fxTransactionRunner,
            FxQuoteMetrics fxQuoteMetrics,
            Clock clock) {
        return new FxQuoteDesk(
                quoteIssuance, quoteLifecycle, pricingPolicyStore, fxAvailability, identityStore,
                idempotentExecutor, fxTransactionRunner, fxQuoteMetrics, clock);
    }

    /** The expiry sweep's schedule - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.fx.quote.expiry.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    FxQuoteExpirySchedule fxQuoteExpirySchedule(
            QuoteLifecycle quoteLifecycle,
            TransactionRunner fxTransactionRunner,
            FxQuoteMetrics fxQuoteMetrics,
            Clock clock,
            @Value("${finapp.fx.quote.expiry.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.fx.quote.expiry.batch:200}") int pageSize) {
        return new FxQuoteExpirySchedule(quoteLifecycle, fxTransactionRunner, fxQuoteMetrics, clock, pollInterval, pageSize);
    }

    /** Whether this instance runs the expiry sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge fxQuoteExpirySweeperEnabled(
            @Value("${finapp.fx.quote.expiry.sweeper.enabled:true}") boolean enabled,
            MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.fx.quote.expiry.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the FX quote expiry sweep")
                .register(meterRegistry);
    }
}
