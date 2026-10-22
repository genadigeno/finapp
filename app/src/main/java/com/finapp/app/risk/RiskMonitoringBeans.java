package com.finapp.app.risk;

import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.JdbcMonitoredTransactionStore;
import com.finapp.risk.JdbcMonitoringWatermarkStore;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredTransactionStore;
import com.finapp.risk.MonitoringIntake;
import com.finapp.risk.MonitoringReprocessing;
import com.finapp.risk.MonitoringWatermarkStore;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the monitoring intake (`P13-TSK-019`): risk's unit of work, the port {@code app} implements over the
 * ledger's stores, the two risk stores, the intake, the reprocessing command, the officer's desk, the meters and the
 * sweep - leaderless, off in test contexts, its gauge eager either way.
 */
@Configuration(proxyBeanMethods = false)
public class RiskMonitoringBeans {

    /** risk's unit of work - one transaction, default propagation; the snapshot read-only REPEATABLE READ. */
    @Bean
    RiskTransactions riskTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new SpringRiskTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    /** risk's step spans - over the platform's domain spans, no attribute ever. */
    @Bean
    RiskFlowScope riskFlowScope(Spans domainSpans) {
        return new RiskFlowScope(domainSpans);
    }

    /**
     * The journal as the intake reads it (ADR-0111 decision 1): the ledger's own journal and chart stores, SELECT only.
     * The chart store is the accounts module's bean by name - two of its type exist (checkout composes its own).
     */
    @Bean
    MonitoredLedger monitoredLedger(JournalEntryStore<Connection> journalEntryStore,
            LedgerAccountStore<Connection> ledgerAccountStore) {
        return new LedgerMonitoredLedger(journalEntryStore, ledgerAccountStore);
    }

    @Bean
    MonitoringWatermarkStore monitoringWatermarkStore() {
        return new JdbcMonitoringWatermarkStore();
    }

    @Bean
    MonitoredTransactionStore monitoredTransactionStore() {
        return new JdbcMonitoredTransactionStore();
    }

    /**
     * T-mon's intake leg. The settle horizon ({@code finapp.risk.monitoring.horizon}) must exceed the longest money
     * transaction plus the instances' clock skew ({@code DESIGN-019} section 0); the batch and the idle cadence pace
     * the sweep.
     */
    @Bean
    MonitoringIntake monitoringIntake(
            MonitoredLedger monitoredLedger,
            MonitoringWatermarkStore monitoringWatermarkStore,
            MonitoredTransactionStore monitoredTransactionStore,
            IdGenerator idGenerator,
            @Value("${finapp.risk.monitoring.horizon:PT60S}") Duration settleHorizon,
            @Value("${finapp.risk.monitoring.batch:200}") int batch,
            @Value("${finapp.risk.monitoring.idle:PT5S}") Duration idleCadence) {
        return new MonitoringIntake(monitoredLedger, monitoringWatermarkStore, monitoredTransactionStore, idGenerator,
                settleHorizon, batch, idleCadence);
    }

    @Bean
    MonitoringReprocessing monitoringReprocessing(
            MonitoringWatermarkStore monitoringWatermarkStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new MonitoringReprocessing(monitoringWatermarkStore, auditWriter, idGenerator, clock);
    }

    @Bean
    RiskMonitoringDesk riskMonitoringDesk(
            MonitoringReprocessing monitoringReprocessing,
            IdempotentExecutor idempotentExecutor,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskMonitoringDesk(monitoringReprocessing, idempotentExecutor, riskTransactions, riskFlowScope);
    }

    /** The lag and unclassified gauges and the claim-failure counter - eager, read behind a 15 s floor. */
    @Bean
    RiskMonitoringMetrics riskMonitoringMetrics(
            MonitoringWatermarkStore monitoringWatermarkStore,
            MonitoredTransactionStore monitoredTransactionStore,
            MonitoredLedger monitoredLedger,
            DataSource dataSource,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new RiskMonitoringMetrics(monitoringWatermarkStore, monitoredTransactionStore, monitoredLedger,
                dataSource::getConnection, clock, meterRegistry);
    }

    /** The monitoring sweep - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(name = "finapp.risk.monitoring.sweeper.enabled", havingValue = "true", matchIfMissing = true)
    RiskMonitoringSchedule riskMonitoringSchedule(
            MonitoringIntake monitoringIntake,
            RiskTransactions riskTransactions,
            RiskMonitoringMetrics riskMonitoringMetrics,
            RiskFlowScope riskFlowScope,
            @Value("${finapp.risk.monitoring.poll:PT5S}") Duration pollInterval,
            @Value("${finapp.risk.monitoring.batches-per-tick:10}") int batchesPerTick) {
        return new RiskMonitoringSchedule(monitoringIntake, riskTransactions, riskMonitoringMetrics, riskFlowScope,
                pollInterval, batchesPerTick);
    }

    /** Whether this instance runs the monitoring sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge riskMonitoringSweeperEnabled(
            @Value("${finapp.risk.monitoring.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.risk.sweeper.enabled", () -> enabled ? 1 : 0)
                .tag("kind", RiskMonitoringMetrics.KIND)
                .description("Whether this instance runs the risk monitoring sweep")
                .register(meterRegistry);
    }
}
