package com.finapp.app.settlement;

import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.app.telemetry.SettlementPullMetrics;
import com.finapp.payments.JdbcSettlementCycleReads;
import com.finapp.payments.SettlementCycleReads;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.JdbcPullPermitStore;
import com.finapp.settlement.PullPermitStore;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementPull;
import com.finapp.settlement.SettlementReportCollector;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The pull's composition (`P8-TSK-021`, ADR-0066 §1): one collector per pulled source, each over
 * its OWN confined credential and present only when its URL is configured — an unconfigured
 * source is simply not pulled, and {@code ProviderTransportGuard} has admitted every configured
 * URL ({@code https} or {@code sftp} off loopback) before any of this exists. The schedule is
 * leaderless and off in test contexts.
 */
@Configuration
public class SettlementPullBeans {

    /** The simulated sources' codes — the compiled register's own. */
    static final String PSP = "simulated-psp.settlement";
    static final String SCHEME = "simulated-scheme.cycle-report";
    static final String PAYOUT = "simulated-payout.settlement";
    static final String BANK = "simulated-bank.statement";

    /** The FX provider's trade report (`P9-TSK-011`) - its code read off the provider's declaration. */
    static final String FX = com.finapp.app.fx.SimulatedFxProviderAdapter.CODE + ".trade-report";

    @Bean
    PullPermitStore<Connection> pullPermitStore() {
        return new JdbcPullPermitStore();
    }

    @Bean
    SettlementCycleReads<Connection> settlementCycleReads() {
        return new JdbcSettlementCycleReads();
    }

    @Bean
    @ConditionalOnProperty("finapp.settlement.psp.report.url")
    SettlementReportCollector pspReportCollector(
            @Value("${finapp.settlement.psp.report.url}") URI url,
            @Value("${finapp.settlement.pull.timeout:PT10S}") Duration timeout,
            @Value("${finapp.settlement.psp.report.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        return new HttpSettlementReportCollector(
                PSP, url, timeout, PspReportKey.decode(configuredKey, loopback(environment)));
    }

    @Bean
    @ConditionalOnProperty("finapp.settlement.scheme.report.url")
    SettlementReportCollector schemeReportCollector(
            @Value("${finapp.settlement.scheme.report.url}") URI url,
            @Value("${finapp.settlement.pull.timeout:PT10S}") Duration timeout,
            @Value("${finapp.settlement.scheme.report.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        return new HttpSettlementReportCollector(
                SCHEME, url, timeout,
                SchemeReportKey.decode(configuredKey, loopback(environment)));
    }

    @Bean
    @ConditionalOnProperty("finapp.settlement.payout.report.url")
    SettlementReportCollector payoutReportCollector(
            @Value("${finapp.settlement.payout.report.url}") URI url,
            @Value("${finapp.settlement.pull.timeout:PT10S}") Duration timeout,
            @Value("${finapp.settlement.payout.report.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        return new HttpSettlementReportCollector(
                PAYOUT, url, timeout,
                PayoutReportKey.decode(configuredKey, loopback(environment)));
    }

    @Bean
    @ConditionalOnProperty("finapp.fx.report.url")
    SettlementReportCollector fxReportCollector(
            @Value("${finapp.fx.report.url}") URI url,
            @Value("${finapp.settlement.pull.timeout:PT10S}") Duration timeout,
            @Value("${finapp.fx.report.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        return new HttpSettlementReportCollector(
                FX, url, timeout, FxReportKey.decode(configuredKey, loopback(environment)));
    }

    @Bean
    @ConditionalOnProperty("finapp.settlement.bank.statement.url")
    SettlementReportCollector bankStatementCollector(
            @Value("${finapp.settlement.bank.statement.url}") URI url,
            @Value("${finapp.settlement.pull.timeout:PT10S}") Duration timeout,
            @Value("${finapp.settlement.bank.statement.key:"
                            + com.finapp.app.mfa.MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        return new HttpSettlementReportCollector(
                BANK, url, timeout,
                BankStatementKey.decode(configuredKey, loopback(environment)));
    }

    @Bean
    SettlementPullMetrics settlementPullMetrics(
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            SettlementSources settlementSources,
            DataSource dataSource,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new SettlementPullMetrics(
                settlementFileStore,
                settlementBatchStore,
                settlementSources,
                dataSource::getConnection,
                clock,
                meterRegistry);
    }

    @Bean
    SettlementPull settlementPull(
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            PullPermitStore<Connection> pullPermitStore,
            FileReception<Connection> fileReception,
            TransactionRunner settlementTransactionRunner,
            SettlementPullMetrics settlementPullMetrics,
            Clock clock,
            List<SettlementReportCollector> collectors) {
        Map<String, SettlementReportCollector> bySource =
                collectors.stream()
                        .collect(
                                Collectors.toMap(
                                        SettlementReportCollector::sourceCode,
                                        Function.identity()));
        return new SettlementPull(
                settlementSources,
                settlementFileStore,
                pullPermitStore,
                fileReception,
                settlementTransactionRunner,
                settlementPullMetrics,
                clock,
                bySource);
    }

    @Bean
    SettlementFetch settlementFetch(
            SettlementPull settlementPull,
            AuditWriter<Connection> auditWriter,
            TransactionRunner settlementTransactionRunner,
            IdGenerator idGenerator,
            Clock clock) {
        return new SettlementFetch(
                settlementPull, auditWriter, settlementTransactionRunner, idGenerator, clock);
    }

    @Bean
    SettlementPullSweep settlementPullSweep(
            SettlementPull settlementPull,
            SettlementSources settlementSources,
            SettlementFileStore<Connection> settlementFileStore,
            SettlementBatchStore<Connection> settlementBatchStore,
            SettlementCycleReads<Connection> settlementCycleReads,
            TransactionRunner settlementTransactionRunner,
            IdGenerator idGenerator,
            Clock clock,
            @Value("${finapp.settlement.pull.window:PT15M}") Duration window,
            @Value("${finapp.settlement.pull.lookback-days:7}") int lookbackDays,
            @Value("${finapp.settlement.pull.cutoff:PT6H}") Duration cutOff,
            @Value("${finapp.settlement.pull.cycle-lag:PT1H}") Duration cycleLag) {
        return new SettlementPullSweep(
                settlementPull,
                settlementSources,
                settlementFileStore,
                settlementBatchStore,
                settlementCycleReads,
                settlementTransactionRunner,
                idGenerator,
                clock,
                window,
                lookbackDays,
                cutOff,
                cycleLag);
    }

    /** The pull's schedule — leaderless on every instance, off in test contexts. */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.settlement.pull.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    SettlementPullSchedule settlementPullSchedule(
            SettlementPullSweep settlementPullSweep,
            @Value("${finapp.settlement.pull.poll:PT5M}") Duration pollInterval) {
        return new SettlementPullSchedule(settlementPullSweep, pollInterval);
    }

    /** Whether this instance runs the pull sweeper — eager either way (`P1-TSK-029`'s rule). */
    @Bean
    io.micrometer.core.instrument.Gauge settlementPullSweeperEnabled(
            @Value("${finapp.settlement.pull.sweeper.enabled:true}") boolean enabled,
            MeterRegistry meterRegistry) {
        return io.micrometer.core.instrument.Gauge.builder(
                        "finapp.settlement.pull.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the settlement pull sweeper")
                .register(meterRegistry);
    }

    private static boolean loopback(Environment environment) {
        return DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
    }
}
