package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.JdbcBnplServicingStore;
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
 * The BNPL servicing wiring (`P12-TSK-015`): the servicing store, T-svc over the purchase store, the pinned terms and the
 * ledger seam, its meters, and the leaderless sweep with its eager {@code finapp.bnpl.servicing.sweeper.enabled} gauge. A
 * configuration of its own beside {@code BnplPurchaseBeans}, so parallel tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplServicingBeans {

    @Bean
    BnplServicingStore bnplServicingStore() {
        return new JdbcBnplServicingStore();
    }

    /** T-svc - one obligation's servicing step: the accrual gate, billing, the applications, the permit. */
    @Bean
    BnplServicing bnplServicing(
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplBook bnplBook,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplServicing(bnplServicingStore, bnplPurchaseStore, bnplTermsStore, bnplBook, outboxWriter,
                idGenerator, clock);
    }

    /** {@code finapp.bnpl.billing{product, outcome}} and {@code finapp.bnpl.servicing{step, outcome}} - every series eager. */
    @Bean
    BnplServicingMetrics bnplServicingMetrics(MeterRegistry meterRegistry) {
        return new BnplServicingMetrics(meterRegistry);
    }

    /** The servicing sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.bnpl.servicing.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    BnplServicingSchedule bnplServicingSchedule(
            BnplServicing bnplServicing,
            BnplTransactions bnplTransactions,
            BnplServicingMetrics bnplServicingMetrics,
            @Value("${finapp.bnpl.servicing.poll:PT30S}") Duration pollInterval,
            @Value("${finapp.bnpl.servicing.batch:50}") int batch,
            @Value("${finapp.bnpl.servicing.retry:PT15M}") Duration retryCadence) {
        return new BnplServicingSchedule(bnplServicing, bnplTransactions, bnplServicingMetrics, pollInterval, batch,
                retryCadence);
    }

    /** Whether this instance runs the servicing sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge bnplServicingSweeperEnabled(
            @Value("${finapp.bnpl.servicing.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.bnpl.servicing.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the BNPL servicing sweep")
                .register(meterRegistry);
    }
}
