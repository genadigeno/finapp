package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAccrualStore;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplClosures;
import com.finapp.bnpl.BnplCollectionStore;
import com.finapp.bnpl.BnplCollections;
import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplConditions;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.JdbcBnplAccrualStore;
import com.finapp.bnpl.JdbcBnplCollectionStore;
import com.finapp.bnpl.JdbcBnplConditionStore;
import com.finapp.bnpl.JdbcBnplServicingStore;
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
 * The BNPL servicing wiring (`P12-TSK-015`; T-acc, its store and meters `P12-TSK-017`'s): the servicing store, T-svc over the purchase store, the pinned terms and the
 * ledger seam, its meters, and the leaderless sweep with its eager {@code finapp.bnpl.servicing.sweeper.enabled} gauge. A
 * configuration of its own beside {@code BnplPurchaseBeans}, so parallel tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplServicingBeans {

    @Bean
    BnplServicingStore bnplServicingStore() {
        return new JdbcBnplServicingStore();
    }

    /**
     * T-svc - one obligation's servicing step: the accrual gate, billing, the applications, the collection, the closure, the
     * condition and the late fee (`P12-TSK-020`), the permit.
     */
    @Bean
    BnplServicing bnplServicing(
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplBook bnplBook,
            BnplClosures bnplClosures,
            BnplCollections bnplCollections,
            BnplConditions bnplConditions,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplServicing(bnplServicingStore, bnplPurchaseStore, bnplTermsStore, bnplBook, bnplClosures,
                bnplCollections, bnplConditions, outboxWriter, idGenerator, clock);
    }

    /** The condition history's and the late fees' store (`P12-TSK-020`, {@code bnpl V013}). */
    @Bean
    BnplConditionStore bnplConditionStore() {
        return new JdbcBnplConditionStore();
    }

    /**
     * T-svc's condition and late-fee slot (`P12-TSK-020`) and the customer read's condition: DPD derived, changes recorded
     * once, the pinned late fee once per instalment, capped.
     */
    @Bean
    BnplConditions bnplConditions(
            BnplConditionStore bnplConditionStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplBook bnplBook,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplConditions(bnplConditionStore, bnplPurchaseStore, bnplTermsStore, bnplBook, outboxWriter,
                idGenerator, clock);
    }

    /** {@code finapp.bnpl.delinquency{product, bucket}} and {@code finapp.bnpl.default{product}} (`P12-TSK-020`). */
    @Bean
    BnplConditionMetrics bnplConditionMetrics(BnplConditionStore bnplConditionStore, DataSource dataSource, Clock clock,
            MeterRegistry meterRegistry) {
        return new BnplConditionMetrics(bnplConditionStore, dataSource::getConnection, clock, meterRegistry);
    }

    /** The collection attempts' store (`P12-TSK-019`, {@code bnpl V011}). */
    @Bean
    BnplCollectionStore bnplCollectionStore() {
        return new JdbcBnplCollectionStore();
    }

    /**
     * T-svc's collection slot (`P12-TSK-019`): what is due, from the customer's wallet alone, through T-rep's posting half -
     * no door, no card instrument (owner decision B6).
     */
    @Bean
    BnplCollections bnplCollections(
            BnplCollectionStore bnplCollectionStore,
            BnplRepayments bnplRepayments,
            BnplPurchaseStore bnplPurchaseStore,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplCollections(bnplCollectionStore, bnplRepayments, bnplPurchaseStore, bnplWallets, bnplBook,
                outboxWriter, idGenerator, clock);
    }

    /** The accrual record's writer (`P12-TSK-017`, {@code bnpl V009}). */
    @Bean
    BnplAccrualStore bnplAccrualStore() {
        return new JdbcBnplAccrualStore();
    }

    /** T-acc - one pay-in-N obligation's accrual, in its own transaction, and the caught-up check every act runs. */
    @Bean
    BnplAccruals bnplAccruals(
            BnplServicingStore bnplServicingStore,
            BnplAccrualStore bnplAccrualStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplTermsStore bnplTermsStore,
            BnplBook bnplBook,
            IdGenerator idGenerator) {
        return new BnplAccruals(bnplServicingStore, bnplAccrualStore, bnplPurchaseStore, bnplTermsStore, bnplBook,
                idGenerator);
    }

    /** {@code finapp.bnpl.accrual{outcome}} and {@code finapp.bnpl.accrual.lag} - every series eager. */
    @Bean
    BnplAccrualMetrics bnplAccrualMetrics(BnplAccrualStore bnplAccrualStore, DataSource dataSource, Clock clock,
            MeterRegistry meterRegistry) {
        return new BnplAccrualMetrics(bnplAccrualStore, dataSource::getConnection, clock, meterRegistry);
    }

    /**
     * {@code finapp.bnpl.billing{product, outcome}}, {@code finapp.bnpl.servicing{step, outcome}} and
     * {@code finapp.bnpl.collection{outcome}} - every series eager; a collection's money also one
     * {@code finapp.bnpl.repayment{kind = auto_collection}} (`P12-TSK-019`).
     */
    @Bean
    BnplServicingMetrics bnplServicingMetrics(MeterRegistry meterRegistry, BnplRepaymentMetrics bnplRepaymentMetrics) {
        return new BnplServicingMetrics(meterRegistry, bnplRepaymentMetrics);
    }

    /** The servicing sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.bnpl.servicing.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    BnplServicingSchedule bnplServicingSchedule(
            BnplServicing bnplServicing,
            BnplAccruals bnplAccruals,
            BnplTransactions bnplTransactions,
            BnplServicingMetrics bnplServicingMetrics,
            BnplAccrualMetrics bnplAccrualMetrics,
            @Value("${finapp.bnpl.servicing.poll:PT30S}") Duration pollInterval,
            @Value("${finapp.bnpl.servicing.batch:50}") int batch,
            @Value("${finapp.bnpl.servicing.retry:PT15M}") Duration retryCadence,
            BnplFlowScope bnplFlowScope) {
        return new BnplServicingSchedule(bnplServicing, bnplAccruals, bnplTransactions, bnplServicingMetrics,
                bnplAccrualMetrics, pollInterval, batch, retryCadence, bnplFlowScope);
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
