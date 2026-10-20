package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplOfferStore;
import com.finapp.bnpl.BnplOffers;
import com.finapp.bnpl.BnplOptions;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProductBounds;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.JdbcBnplOfferStore;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
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

/**
 * Wiring for the BNPL offer and the origination schedule (`P12-TSK-011`): bnpl's offer store, the origination step over
 * the application store, credit's decision read, checkout's session and the terms pin; its leaderless sweep with the
 * sweeper gauge (off in test contexts); the three hints (credit's decision recorded and request closed, checkout's
 * session expired); the customer's offer doors and the checkout's options; the {@code finapp.bnpl.offer},
 * {@code finapp.bnpl.eligibility} and {@code finapp.bnpl.origination} meters. A configuration of its own, so parallel
 * tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplOfferBeans {

    @Bean
    BnplOfferStore bnplOfferStore() {
        return new JdbcBnplOfferStore();
    }

    /** The origination step (T-off) and the expiry - the system's acts on applications and offers. */
    @Bean
    BnplOrigination bnplOrigination(
            BnplApplicationStore bnplApplicationStore,
            BnplOfferStore bnplOfferStore,
            BnplTermsAdministration bnplTermsAdministration,
            BnplCreditDecisions bnplCreditDecisions,
            BnplCheckoutSessions bnplCheckoutSessions,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplOrigination(bnplApplicationStore, bnplOfferStore, bnplTermsAdministration, bnplCreditDecisions,
                bnplCheckoutSessions, outboxWriter, idGenerator, clock);
    }

    /** The customer's offers - the read and the decline. */
    @Bean
    BnplOffers bnplOffers(
            BnplOfferStore bnplOfferStore,
            BnplTermsStore bnplTermsStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplOffers(bnplOfferStore, bnplTermsStore, outboxWriter, auditWriter, idGenerator, clock);
    }

    /** The checkout's BNPL options - products and illustrative plans, no eligibility. */
    @Bean
    BnplOptions bnplOptions(
            BnplCheckoutSessions bnplCheckoutSessions,
            BnplMerchants bnplMerchants,
            BnplProductBounds bnplProductBounds,
            BnplTermsAdministration bnplTermsAdministration,
            BnplOfferStore bnplOfferStore) {
        return new BnplOptions(bnplCheckoutSessions, bnplMerchants, bnplProductBounds, bnplTermsAdministration,
                bnplOfferStore);
    }

    /** {@code finapp.bnpl.offer}, {@code finapp.bnpl.eligibility}, {@code finapp.bnpl.origination} - every series eager. */
    @Bean
    BnplOriginationMetrics bnplOriginationMetrics(MeterRegistry meterRegistry) {
        return new BnplOriginationMetrics(meterRegistry);
    }

    @Bean
    BnplOfferDesk bnplOfferDesk(
            BnplOffers bnplOffers,
            BnplOptions bnplOptions,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplOriginationMetrics bnplOriginationMetrics) {
        return new BnplOfferDesk(bnplOffers, bnplOptions, identityStore, mfaEnrolmentStore, idempotentExecutor,
                bnplTransactions, bnplOriginationMetrics);
    }

    /** The origination sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.bnpl.origination.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    BnplOriginationSchedule bnplOriginationSchedule(
            BnplOrigination bnplOrigination,
            BnplTransactions bnplTransactions,
            BnplOriginationMetrics bnplOriginationMetrics,
            @Value("${finapp.bnpl.origination.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.bnpl.origination.batch:20}") int batch,
            @Value("${finapp.bnpl.origination.permit:PT30S}") Duration permit,
            BnplFlowScope bnplFlowScope) {
        return new BnplOriginationSchedule(bnplOrigination, bnplTransactions, bnplOriginationMetrics, pollInterval, batch,
                permit, bnplFlowScope);
    }

    /** Whether this instance runs the origination sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge bnplOriginationSweeperEnabled(
            @Value("${finapp.bnpl.origination.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.bnpl.origination.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the BNPL origination sweep")
                .register(meterRegistry);
    }

    /** {@code credit.CreditDecisionRecorded} as a hint. */
    @Bean
    BnplOriginationHint bnplOriginationDecisionHint(BnplOrigination bnplOrigination) {
        return new BnplOriginationHint(bnplOrigination, BnplOriginationHint.Kind.DECISION_RECORDED);
    }

    /** {@code credit.CreditDecisionRequestClosed} as a hint. */
    @Bean
    BnplOriginationHint bnplOriginationClosureHint(BnplOrigination bnplOrigination) {
        return new BnplOriginationHint(bnplOrigination, BnplOriginationHint.Kind.REQUEST_CLOSED);
    }

    /** {@code checkout.CheckoutSessionExpired} as a hint. */
    @Bean
    BnplOriginationHint bnplOriginationSessionHint(BnplOrigination bnplOrigination) {
        return new BnplOriginationHint(bnplOrigination, BnplOriginationHint.Kind.SESSION_EXPIRED);
    }
}
