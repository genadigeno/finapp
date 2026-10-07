package com.finapp.app.crossborder;

import com.finapp.app.payments.CorridorCallbackService;
import com.finapp.app.payments.CorridorWebhookKey;
import com.finapp.app.payments.OutboundCreditResolutionSchedule;
import com.finapp.app.payments.OutboundReturnSchedule;
import com.finapp.app.payments.OutboundReturnWorker;
import com.finapp.crossborder.OfferStore;
import com.finapp.crossborder.PaymentProgress;
import com.finapp.crossborder.PaymentStore;
import com.finapp.fx.CrossBorderCompletionBooking;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.TradeStore;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.payments.JdbcOutboundCreditReturnStore;
import com.finapp.payments.OutboundCreditComposition;
import com.finapp.payments.OutboundCreditOutcomes;
import com.finapp.payments.OutboundCreditResolution;
import com.finapp.payments.OutboundCreditReturnStore;
import com.finapp.payments.OutboundCreditReturns;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.RailOperations;
import com.finapp.payments.SchemeExecutionClaimStore;
import com.finapp.payments.SettlementExpectations;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wiring for the cross-border outbound credit's resolution and completion (`P9-TSK-020`): fx's completion
 * booking, crossborder's payment progress, their composition for payments ({@link CrossBorderCompletion}), the
 * one applier ({@link OutboundCreditOutcomes}), the resolution and its leaderless schedule, and the corridor
 * callback door's HMAC and service.
 */
@Configuration(proxyBeanMethods = false)
public class CrossBorderOutcomeBeans {

    /**
     * The cross-border payment's meters (`P9-TSK-027`, PHASE_9_PLAN.md section 15): eager per corridor the build can
     * carry; the in-transit age read from the shared database on its own connection. Unconditional - the store is.
     */
    @Bean
    com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics(
            javax.sql.DataSource dataSource, Clock clock, MeterRegistry meterRegistry) {
        com.finapp.crossborder.PaymentStore payments = new com.finapp.crossborder.JdbcPaymentStore();
        return new com.finapp.app.telemetry.CrossBorderMetrics(com.finapp.app.payments.PaymentBeans.CORRIDOR_DECLARATIONS,
                payments::oldestInTransit, dataSource::getConnection, clock, meterRegistry);
    }

    @Bean
    CrossBorderCompletionBooking crossBorderCompletionBooking(
            QuoteStore quoteStore,
            TradeStore tradeStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.fx.CoverUnwinds coverUnwinds) {
        return new CrossBorderCompletionBooking(quoteStore, tradeStore, new ChartOfAccounts<>(ledgerAccountStore),
                outboxWriter, idGenerator, clock, coverUnwinds);
    }

    @Bean
    PaymentProgress crossBorderPaymentProgress(
            PaymentStore crossBorderPaymentStore,
            OfferStore crossBorderOfferStore,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new PaymentProgress(crossBorderPaymentStore, crossBorderOfferStore, outboxWriter, idGenerator);
    }

    @Bean
    OutboundCreditComposition<Connection> crossBorderCompletion(
            PaymentProgress crossBorderPaymentProgress,
            CrossBorderCompletionBooking crossBorderCompletionBooking,
            Clock clock,
            com.finapp.fx.ConversionParticipants conversionParticipants,
            LedgerAccountStore<Connection> ledgerAccountStore,
            MeterRegistry meterRegistry,
            com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics) {
        return new CrossBorderCompletion(crossBorderPaymentProgress, crossBorderCompletionBooking, clock,
                conversionParticipants, new ChartOfAccounts<>(ledgerAccountStore), meterRegistry, crossBorderMetrics);
    }

    @Bean
    OutboundCreditReturnStore outboundCreditReturnStore() {
        return new JdbcOutboundCreditReturnStore();
    }

    /** The one return applier (`P9-TSK-023`): the inquiry and the report worker both hand it a locked credit. */
    @Bean
    OutboundCreditReturns outboundCreditReturns(
            OutboundCreditReturnStore outboundCreditReturnStore,
            PostingService postingService,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PaymentRails paymentRails,
            SettlementExpectations settlementExpectations,
            OutboundCreditComposition<Connection> crossBorderCompletion,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new OutboundCreditReturns(outboundCreditReturnStore, postingService,
                new ChartOfAccounts<>(ledgerAccountStore), paymentRails, settlementExpectations, crossBorderCompletion,
                auditWriter, idGenerator, clock);
    }

    /** A parked cross-border return's four-eyes resolution, over payments and crossborder (`P9-TSK-023`, T-g). */
    @Bean
    com.finapp.reconciliation.ResolvedCorridorReturns resolvedCorridorReturns(
            OutboundCreditStore outboundCreditStore,
            OutboundCreditReturnStore outboundCreditReturnStore,
            PaymentProgress crossBorderPaymentProgress,
            com.finapp.fx.ConversionParticipants conversionParticipants,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PostingService postingService,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            MeterRegistry meterRegistry,
            com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics,
            SchemeExecutionClaimStore<Connection> schemeExecutionClaimStore) {
        return new CorridorReturnResolutions(outboundCreditStore, outboundCreditReturnStore, crossBorderPaymentProgress,
                conversionParticipants, new ChartOfAccounts<>(ledgerAccountStore), postingService, auditWriter,
                idGenerator, clock, meterRegistry, crossBorderMetrics, schemeExecutionClaimStore,
                com.finapp.app.payments.PaymentBeans.CORRIDOR_DECLARATIONS.stream()
                        .map(com.finapp.payments.CorridorDeclaration::rail).toList());
    }

    /** The corridor report's return channel (`P9-TSK-023`): the corridor-scoped waiting returns, one per transaction. */
    @Bean
    OutboundReturnWorker outboundReturnWorker(
            TransactionRunner paymentTransactionRunner,
            com.finapp.reconciliation.WaitingPayoutReturns waitingCorridorReturns,
            OutboundCreditStore outboundCreditStore,
            SchemeExecutionClaimStore<Connection> schemeExecutionClaimStore,
            OutboundCreditReturns outboundCreditReturns,
            MeterRegistry meterRegistry,
            @Value("${finapp.payments.outbound.return.sweeper.batch:25}") int batch,
            com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics,
            PaymentStore crossBorderPaymentStore,
            com.finapp.platform.telemetry.Spans domainSpans) {
        return new OutboundReturnWorker(paymentTransactionRunner, waitingCorridorReturns, outboundCreditStore,
                schemeExecutionClaimStore, com.finapp.app.payments.PaymentBeans.CORRIDOR_DECLARATIONS.stream()
                        .map(com.finapp.payments.CorridorDeclaration::rail).toList(),
                outboundCreditReturns, meterRegistry, batch, crossBorderMetrics, crossBorderPaymentStore, domainSpans);
    }

    /** Leaderless on every instance; off in test contexts, where the suites drive the sweep. */
    @Bean
    @ConditionalOnProperty(name = "finapp.payments.outbound.return.sweeper.enabled", havingValue = "true",
            matchIfMissing = true)
    OutboundReturnSchedule outboundReturnSchedule(
            OutboundReturnWorker outboundReturnWorker,
            @Value("${finapp.payments.outbound.return.sweeper.poll:PT30S}") Duration poll) {
        return new OutboundReturnSchedule(outboundReturnWorker, poll);
    }

    @Bean
    Gauge outboundReturnSweeperEnabled(
            @Value("${finapp.payments.outbound.return.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.payments.outbound.return.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the corridor return sweep")
                .register(meterRegistry);
    }

    @Bean
    OutboundCreditOutcomes outboundCreditOutcomes(
            OutboundCreditStore outboundCreditStore,
            HoldService holdService,
            PostingService postingService,
            LedgerAccountStore<Connection> ledgerAccountStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            PaymentRails paymentRails,
            SchemeExecutionClaimStore<Connection> schemeExecutionClaimStore,
            SettlementExpectations settlementExpectations,
            OutboundCreditComposition<Connection> crossBorderCompletion,
            OutboundCreditReturns outboundCreditReturns) {
        return new OutboundCreditOutcomes(outboundCreditStore, holdService, postingService,
                new ChartOfAccounts<>(ledgerAccountStore), auditWriter, idGenerator, clock, paymentRails,
                schemeExecutionClaimStore, settlementExpectations, crossBorderCompletion, outboundCreditReturns);
    }

    @Bean
    OutboundCreditResolution outboundCreditResolution(
            OutboundCreditStore outboundCreditStore,
            OutboundCreditOutcomes outboundCreditOutcomes,
            RailOperations railOperations,
            PaymentRails paymentRails,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            @Value(OutboundCreditResolutionSchedule.DISPATCHED_AGE) Duration dispatchedAge,
            @Value("${finapp.payments.outbound.sweeper.unknown-age:PT1M}") Duration unknownAge,
            @Value("${finapp.payments.outbound.sweeper.received-age:PT2M}") Duration receivedAge,
            @Value("${finapp.payments.outbound.sweeper.delivery-age:PT10M}") Duration deliveryAge,
            @Value("${finapp.payments.outbound.sweeper.margin:PT5M}") Duration margin,
            @Value("${finapp.payments.outbound.sweeper.batch:25}") int batch,
            IdGenerator idGenerator,
            Clock clock,
            TransactionRunner paymentTransactionRunner,
            OutboundCreditReturnStore outboundCreditReturnStore) {
        return new OutboundCreditResolution(outboundCreditStore, outboundCreditOutcomes, railOperations, paymentRails,
                providerEvidenceStore, new OutboundCreditResolution.Config(dispatchedAge, unknownAge, receivedAge,
                        deliveryAge, margin, batch),
                idGenerator, clock, paymentTransactionRunner, outboundCreditReturnStore);
    }

    /** Leaderless on every instance; off in test contexts, where the suites drive the sweep. */
    @Bean
    @ConditionalOnProperty(name = "finapp.payments.outbound.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    OutboundCreditResolutionSchedule outboundCreditResolutionSchedule(
            OutboundCreditResolution outboundCreditResolution,
            @Value("${finapp.payments.outbound.sweeper.poll:PT30S}") Duration poll) {
        return new OutboundCreditResolutionSchedule(outboundCreditResolution, poll);
    }

    @Bean
    Gauge outboundCreditSweeperEnabled(
            @Value("${finapp.payments.outbound.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.payments.outbound.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the outbound credit resolution sweep")
                .register(meterRegistry);
    }

    /** The corridor callback door's HMAC - its own key, never the outbound API key. */
    @Bean
    @ConditionalOnProperty("finapp.corridor.provider.url")
    WebhookSignature corridorWebhookSignature(
            @Value("${finapp.corridor.webhook.key:" + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}") String configuredKey,
            @Value("${finapp.corridor.webhook.tolerance:PT5M}") Duration tolerance,
            Environment environment,
            Clock clock) {
        boolean loopback = com.finapp.app.security.DatabaseEndpoint.isEntirelyLoopback(
                com.finapp.app.security.DatabaseEndpoint.url(environment));
        return new WebhookSignature(CorridorWebhookKey.decode(configuredKey, loopback), tolerance, clock);
    }

    /** The corridor callback door: evidence first, the inbox, then the inquiry the hint triggers. */
    @Bean
    @ConditionalOnProperty("finapp.corridor.provider.url")
    CorridorCallbackService corridorCallbackService(
            WebhookSignature corridorWebhookSignature,
            com.finapp.payments.CorridorRail corridorRail,
            OutboundCreditStore outboundCreditStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            InboxConsumer<Connection> inboxConsumer,
            OutboundCreditResolution outboundCreditResolution,
            TransactionRunner paymentTransactionRunner,
            tools.jackson.databind.ObjectMapper objectMapper,
            Clock clock) {
        return new CorridorCallbackService(corridorWebhookSignature, corridorRail.id().value(),
                outboundCreditStore, providerEvidenceStore, inboxConsumer, outboundCreditResolution,
                paymentTransactionRunner, objectMapper, clock);
    }
}
