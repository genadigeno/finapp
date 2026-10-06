package com.finapp.app.fx;

import com.finapp.fx.CoverStore;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxCoverOutcomes;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.FxProviders;
import com.finapp.fx.FxSettlementExpectations;
import com.finapp.fx.JdbcCoverStore;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.RateSnapshotStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
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
 * The cover's composition (`P9-TSK-012`; ADR-0077): the store, the one outcome applier, the wire
 * legs, the leaderless sweep, the meters and the post-commit nudge. The provider adapters come from
 * {@link FxProviders} - a cover whose provider is not composed on an instance simply waits there.
 */
@Configuration
public class FxCoverBeans {

    @Bean
    CoverStore coverStore() {
        return new JdbcCoverStore();
    }

    @Bean
    FxCoverMetrics fxCoverMetrics(CoverStore coverStore, DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        return new FxCoverMetrics(FxProviderBeans.DECLARED.keySet(), coverStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** The wanted-position rule (`P9-TSK-021`): the cover applier's and the abandonment writer's alike. */
    @Bean
    com.finapp.fx.CoverUnwinds coverUnwinds(
            CoverStore coverStore, AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new com.finapp.fx.CoverUnwinds(coverStore, auditWriter, idGenerator, clock);
    }

    @Bean
    FxCoverOutcomes fxCoverOutcomes(
            CoverStore coverStore,
            QuoteStore quoteStore,
            FxProviders fxProviders,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PostingService postingService,
            FxSettlementExpectations fxSettlementExpectations,
            FxProviderEvidenceStore<Connection> fxProviderEvidenceStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.fx.CoverUnwinds coverUnwinds) {
        return new FxCoverOutcomes(
                coverStore, quoteStore, fxProviders, new ChartOfAccounts<>(ledgerAccountStore), postingService,
                fxSettlementExpectations, fxProviderEvidenceStore, outboxWriter, auditWriter, idGenerator, clock,
                coverUnwinds);
    }

    @Bean
    FxCoverDispatch fxCoverDispatch(
            CoverStore coverStore,
            FxCoverOutcomes fxCoverOutcomes,
            FxProviders fxProviders,
            QuoteStore quoteStore,
            PricingPolicyStore pricingPolicyStore,
            RateSnapshotStore<Connection> rateSnapshotStore,
            FxProviderEvidenceStore<Connection> fxProviderEvidenceStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            TransactionRunner fxTransactionRunner,
            FxCoverMetrics fxCoverMetrics,
            Clock clock,
            @Value("${finapp.fx.cover.resend-after:PT30S}") Duration resendAfter,
            @Value("${finapp.fx.cover.requote-base:PT30S}") Duration requoteBase,
            @Value("${finapp.fx.cover.batch:50}") int batch) {
        return new FxCoverDispatch(
                coverStore, fxCoverOutcomes, fxProviders, quoteStore, pricingPolicyStore, rateSnapshotStore,
                fxProviderEvidenceStore, outboxWriter, auditWriter, idGenerator, fxTransactionRunner, fxCoverMetrics,
                clock, new FxCoverDispatch.Config(resendAfter, requoteBase, batch));
    }

    /** The cover sweep's schedule - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.fx.cover.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    FxCoverSchedule fxCoverSchedule(
            FxCoverDispatch fxCoverDispatch, @Value("${finapp.fx.cover.poll:PT5S}") Duration pollInterval) {
        return new FxCoverSchedule(fxCoverDispatch, pollInterval);
    }

    /** Whether this instance runs the cover sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge fxCoverSweeperEnabled(
            @Value("${finapp.fx.cover.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.fx.cover.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the FX cover sweep")
                .register(meterRegistry);
    }

    /** The callback door's HMAC (`P9-TSK-012`) - its own key, never the outbound API key. */
    @Bean
    @ConditionalOnProperty("finapp.fx.provider.url")
    com.finapp.payments.WebhookSignature fxWebhookSignature(
            @Value("${finapp.fx.webhook.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}") String configuredKey,
            @Value("${finapp.fx.webhook.tolerance:PT5M}") Duration tolerance,
            org.springframework.core.env.Environment environment,
            Clock clock) {
        boolean loopback = com.finapp.app.security.DatabaseEndpoint.isEntirelyLoopback(
                com.finapp.app.security.DatabaseEndpoint.url(environment));
        return new com.finapp.payments.WebhookSignature(FxWebhookKey.decode(configuredKey, loopback), tolerance, clock);
    }

    /** The callback door: evidence first, the inbox, then the inquiry the hint triggers. */
    @Bean
    @ConditionalOnProperty("finapp.fx.provider.url")
    FxCallbackService fxCallbackService(
            com.finapp.payments.WebhookSignature fxWebhookSignature,
            FxProviderEvidenceStore<Connection> fxProviderEvidenceStore,
            com.finapp.platform.inbox.InboxConsumer<Connection> inboxConsumer,
            FxCoverDispatch fxCoverDispatch,
            TransactionRunner fxTransactionRunner,
            tools.jackson.databind.ObjectMapper objectMapper,
            Clock clock) {
        return new FxCallbackService(
                fxWebhookSignature, SimulatedFxProviderAdapter.CODE, fxProviderEvidenceStore, inboxConsumer,
                fxCoverDispatch, fxTransactionRunner, objectMapper, clock);
    }

    /**
     * The booked conversion's post-commit nudge - a hint; the sweep is the guarantee. Off in test
     * contexts ({@code finapp.fx.cover.nudge.enabled}), where the suites drive the dispatch.
     */
    @Bean
    com.finapp.fx.CoverDispatchNudge coverDispatchNudge(
            FxCoverDispatch fxCoverDispatch,
            @Value("${finapp.fx.cover.nudge.enabled:true}") boolean enabled,
            @Value("${finapp.fx.cover.nudge.threads:2}") int threads,
            @Value("${finapp.fx.cover.nudge.queue:256}") int queue) {
        return enabled ? new FxCoverNudge(fxCoverDispatch, threads, queue) : com.finapp.fx.CoverDispatchNudge.NONE;
    }
}
