package com.finapp.app.telemetry;

import com.finapp.platform.outbox.OutboxBacklog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import io.opentelemetry.sdk.trace.SpanProcessor;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the tracing implementation, which is the composition root's job.
 *
 * <p>{@code platform} records spans through a facade and names the attributes; the decision about
 * <em>what</em> records them — here, the OpenTelemetry SDK that {@code SYSTEM_ARCHITECTURE.md}
 * names — is made once, here. The same split as slf4j: a library that bound an implementation
 * would impose it on every consumer.
 */
@Configuration
class TelemetryConfiguration {

    /**
     * Stamps correlation onto every span the SDK starts.
     *
     * <p>Contributed as a bean rather than installed by hand so it composes with whatever else
     * exports spans, instead of replacing it.
     */
    @Bean
    SpanProcessor correlationSpanProcessor() {
        return new CorrelationSpanProcessor();
    }

    /**
     * The outbox backlog, as gauges (`P0-TSK-029`, paying down the debt `P0-TSK-020` recorded).
     *
     * <p>Reads through the application's own {@code DataSource}, so it measures what the
     * application can actually see - and so it goes through the traced wrapper and the pool the
     * rest of the platform uses, rather than opening a private connection that would report health
     * the application does not have.
     *
     * <p>Depth and age only. Relay throughput, failure and dead-letter counts come from
     * {@code RelayPollResult} and need a relay to be running; nothing schedules one yet, so
     * recording them here would produce meters that are structurally always zero - which reads as
     * "nothing is failing" rather than "nothing is running". Recorded as debt rather than faked.
     */
    @Bean
    OutboxMetrics outboxMetrics(DataSource dataSource, Clock clock, MeterRegistry registry) {
        return new OutboxMetrics(new OutboxBacklog(dataSource::getConnection), clock, registry);
    }

    /**
     * How many sessions are live, as a gauge (`P1-TSK-029`, criterion 6).
     *
     * <p>Reads through the application's own {@code DataSource} for {@code outboxMetrics}' reason:
     * it measures what the application can actually see, through the pool the rest of the platform
     * uses, rather than opening a private connection that would report health the application does
     * not have.
     */
    @Bean
    IdentityMetrics identityMetrics(
            com.finapp.identity.SessionStore<java.sql.Connection> sessionStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry) {
        return new IdentityMetrics(sessionStore, dataSource::getConnection, clock, registry);
    }

    /**
     * The manual-review queue depth, as a gauge (`P2-TSK-010`, `PHASE_2_PLAN.md` §10).
     *
     * <p>Arrives with the queue it measures — the trigger-reached rule: a gauge registered before
     * `P2-TSK-010` would have been structurally always zero, which reads as "nobody is waiting"
     * rather than "nothing exists yet". Same {@code DataSource} reasoning as its siblings.
     */
    @Bean
    KycMetrics kycMetrics(
            com.finapp.kyc.ReviewTaskStore<java.sql.Connection> reviewTaskStore,
            DataSource dataSource,
            Clock clock,
            MeterRegistry registry) {
        return new KycMetrics(reviewTaskStore, dataSource::getConnection, clock, registry);
    }

    /**
     * The balance-projection drift, as a gauge (`P3-TSK-010`, ADR-0041 rule 2).
     *
     * <p>The verification job's whole schedule is this gauge's cache floor: a scrape past the
     * floor recomputes every balance from postings and compares — read-only, idempotent, no
     * leader, no ambient schedule, so no {@code DISTRIBUTED_EXECUTION.md} §3 question arises.
     * Same {@code DataSource} reasoning as its siblings: it verifies what the application can
     * actually see, through the pool the writes go through.
     */
    @Bean
    LedgerMetrics ledgerMetrics(DataSource dataSource, Clock clock, MeterRegistry registry) {
        com.finapp.ledger.ProjectionVerification verification =
                new com.finapp.ledger.ProjectionVerification(
                        new com.finapp.ledger.JdbcBalanceDerivation());
        return new LedgerMetrics(
                verification::verify, dataSource::getConnection, clock, registry);
    }

    /**
     * Wraps the auto-configured connection pool so acquiring a connection is visible in a trace.
     *
     * <p><strong>A {@code BeanPostProcessor} because a {@code @Bean} cannot do this.</strong>
     * Boot's data-source auto-configuration is conditional on no {@code DataSource} bean existing,
     * so declaring one here would not decorate the pool — it would prevent the pool from being
     * created at all, and leave this wrapping nothing. Decoration after the fact is the only
     * mechanism that composes with a conditional auto-configuration.
     *
     * <p>The {@link Tracer} is taken through an {@link ObjectProvider} and resolved lazily. A
     * post-processor is constructed very early, and demanding a fully-built tracing stack at that
     * point makes the data source depend on the order two unrelated auto-configurations happen to
     * initialise in — which works until the day it does not.
     */
    @Bean
    static BeanPostProcessor traceDataSourceConnections(ObjectProvider<Tracer> tracer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof DataSource pool && !(bean instanceof TracedDataSource)) {
                    return new TracedDataSource(pool, tracer::getObject);
                }
                return bean;
            }
        };
    }
}
