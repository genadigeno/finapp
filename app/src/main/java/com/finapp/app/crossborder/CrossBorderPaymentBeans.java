package com.finapp.app.crossborder;

import com.finapp.crossborder.BeneficiaryStore;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CounterpartyScreening;
import com.finapp.crossborder.CrossBorderExecution;
import com.finapp.crossborder.CrossBorderFx;
import com.finapp.crossborder.CrossBorderLimitCheck;
import com.finapp.crossborder.CrossBorderRiskDecision;
import com.finapp.crossborder.JdbcPaymentStore;
import com.finapp.crossborder.OfferStore;
import com.finapp.crossborder.PaymentAuthorization;
import com.finapp.crossborder.PaymentStore;
import com.finapp.crossborder.PermitAllUntilPhase13;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.ledger.HoldService;
import com.finapp.payments.JdbcOutboundCreditStore;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.RailOperations;
import com.finapp.payments.RoutingStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the cross-border payment's authorization and dispatch (`P9-TSK-019`): the payment and outbound
 * credit stores, {@link CrossBorderExecution} over payments and ledger, the Phase 13 seams as
 * {@link PermitAllUntilPhase13} - named for what it is - the authorization and the door's desk.
 */
@Configuration(proxyBeanMethods = false)
public class CrossBorderPaymentBeans {

    @Bean
    PaymentStore crossBorderPaymentStore() {
        return new JdbcPaymentStore();
    }

    @Bean
    OutboundCreditStore outboundCreditStore() {
        return new JdbcOutboundCreditStore();
    }

    /** The limit seam - permits everything until Phase 13 (ADR-0081 point 8); the parameter is the control. */
    @Bean
    CrossBorderLimitCheck<Connection> crossBorderLimitCheck() {
        return new PermitAllUntilPhase13<>();
    }

    /** The risk seam - permits everything until Phase 13 (ADR-0081 point 8). */
    @Bean
    CrossBorderRiskDecision<Connection> crossBorderRiskDecision() {
        return new PermitAllUntilPhase13<>();
    }

    @Bean
    CrossBorderExecution crossBorderExecution(
            RoutingStore<Connection> routingStore,
            PaymentRails paymentRails,
            RailOperations railOperations,
            HoldService holdService,
            OutboundCreditStore outboundCreditStore,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            IdGenerator idGenerator,
            Clock clock,
            com.finapp.payments.OutboundCreditOutcomes outboundCreditOutcomes) {
        return new PaymentsCrossBorderExecution(routingStore, paymentRails, railOperations, holdService, outboundCreditStore,
                providerEvidenceStore, idGenerator, clock, outboundCreditOutcomes);
    }

    @Bean
    PaymentAuthorization crossBorderPaymentAuthorization(
            PaymentStore crossBorderPaymentStore,
            OfferStore crossBorderOfferStore,
            BeneficiaryStore crossBorderBeneficiaryStore,
            CorridorPolicyStore corridorPolicyStore,
            CorridorAvailabilityStore corridorAvailabilityStore,
            CounterpartyScreening counterpartyScreening,
            CrossBorderRiskDecision<Connection> crossBorderRiskDecision,
            CrossBorderLimitCheck<Connection> crossBorderLimitCheck,
            CrossBorderFx crossBorderFx,
            CrossBorderExecution crossBorderExecution,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new PaymentAuthorization(crossBorderPaymentStore, crossBorderOfferStore, crossBorderBeneficiaryStore,
                corridorPolicyStore, corridorAvailabilityStore, counterpartyScreening, crossBorderRiskDecision,
                crossBorderLimitCheck, crossBorderFx, crossBorderExecution, auditWriter, outboxWriter, idGenerator);
    }

    @Bean
    CrossBorderPaymentDesk crossBorderPaymentDesk(
            PaymentAuthorization crossBorderPaymentAuthorization,
            CrossBorderFx crossBorderFx,
            CrossBorderExecution crossBorderExecution,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner crossborderTransactionRunner,
            MeterRegistry meterRegistry,
            Clock clock,
            com.finapp.crossborder.PaymentCancellation crossBorderPaymentCancellation,
            com.finapp.crossborder.CancellationStore crossBorderCancellationStore,
            com.finapp.app.telemetry.CrossBorderMetrics crossBorderMetrics) {
        return new CrossBorderPaymentDesk(crossBorderPaymentAuthorization, crossBorderFx, crossBorderExecution, identityStore,
                mfaEnrolmentStore, idempotentExecutor, crossborderTransactionRunner, meterRegistry, clock,
                crossBorderPaymentCancellation, crossBorderCancellationStore, crossBorderMetrics);
    }

    @Bean
    com.finapp.crossborder.CancellationStore crossBorderCancellationStore() {
        return new com.finapp.crossborder.JdbcCancellationStore();
    }

    /** The customer's recall request (`P9-TSK-024`): the credit marked, the born-once request, in one transaction. */
    @Bean
    com.finapp.crossborder.PaymentCancellation crossBorderPaymentCancellation(
            PaymentStore crossBorderPaymentStore,
            com.finapp.crossborder.CancellationStore crossBorderCancellationStore,
            CrossBorderExecution crossBorderExecution,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator) {
        return new com.finapp.crossborder.PaymentCancellation(crossBorderPaymentStore, crossBorderCancellationStore,
                crossBorderExecution, outboxWriter, auditWriter, idGenerator);
    }
}
