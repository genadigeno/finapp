package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplDisputeStore;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRefundStore;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.JdbcBnplDisputeStore;
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
 * The BNPL dispute wiring (`P12-TSK-023`): the store, T-dsp ({@link BnplDisputes} over T-ref's body), whose party an
 * operator is (credit's {@code IdentityActingParty}, one read for credit, lending and bnpl), the meter, the desk, and the
 * leaderless deadline sweep with its eager {@code finapp.bnpl.dispute.sweeper.enabled} gauge. A configuration of its own,
 * so parallel tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplDisputeBeans {

    @Bean
    BnplDisputeStore bnplDisputeStore() {
        return new JdbcBnplDisputeStore();
    }

    /** T-dsp - the dispute's acts under B5, B4 and B6, a refund outcome through T-ref's body in the same transaction. */
    @Bean
    BnplDisputes bnplDisputes(
            BnplDisputeStore bnplDisputeStore,
            BnplRefundStore bnplRefundStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplRefunds bnplRefunds,
            IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplDisputes(bnplDisputeStore, bnplRefundStore, bnplServicingStore, bnplPurchaseStore, bnplTermsStore,
                bnplRefunds, new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter,
                idGenerator, clock);
    }

    /** {@code finapp.bnpl.dispute{outcome}} - every series eager. */
    @Bean
    BnplDisputeMetrics bnplDisputeMetrics(MeterRegistry meterRegistry) {
        return new BnplDisputeMetrics(meterRegistry);
    }

    @Bean
    BnplDisputeDesk bnplDisputeDesk(
            BnplDisputes bnplDisputes,
            BnplAccruals bnplAccruals,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplDisputeMetrics bnplDisputeMetrics,
            BnplRefundMetrics bnplRefundMetrics) {
        return new BnplDisputeDesk(bnplDisputes, bnplAccruals, identityStore, mfaEnrolmentStore, idempotentExecutor,
                bnplTransactions, bnplDisputeMetrics, bnplRefundMetrics);
    }

    /** The dispute deadline sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.bnpl.dispute.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    BnplDisputeSchedule bnplDisputeSchedule(
            BnplDisputes bnplDisputes,
            BnplAccruals bnplAccruals,
            BnplTransactions bnplTransactions,
            BnplDisputeMetrics bnplDisputeMetrics,
            BnplRefundMetrics bnplRefundMetrics,
            IdGenerator idGenerator,
            @Value("${finapp.bnpl.dispute.poll:PT60S}") Duration pollInterval,
            @Value("${finapp.bnpl.dispute.batch:50}") int batch,
            @Value("${finapp.bnpl.dispute.retry:PT15M}") Duration retryCadence) {
        return new BnplDisputeSchedule(bnplDisputes, bnplAccruals, bnplTransactions, bnplDisputeMetrics, bnplRefundMetrics,
                idGenerator, pollInterval, batch, retryCadence);
    }

    /** Whether this instance runs the dispute deadline sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge bnplDisputeSweeperEnabled(
            @Value("${finapp.bnpl.dispute.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.bnpl.dispute.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the BNPL dispute deadline sweep")
                .register(meterRegistry);
    }
}
