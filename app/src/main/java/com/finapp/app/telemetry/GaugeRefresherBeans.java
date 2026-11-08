package com.finapp.app.telemetry;

import com.finapp.platform.metrics.GaugeRefresher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every gauge off the scrape thread (`P15-TSK-024`; ADR-0124 decision 7): the one {@link GaugeRefresher} of this instance,
 * the application's Prometheus registry that hands it every {@code finapp.} meter's function ({@link OffScrapeMeterRegistry}),
 * the refresher's place in the context's lifecycle, and its canary.
 *
 * <p>The registry is declared here, so the auto-configured one ({@code @ConditionalOnMissingBean}) stands back; it is
 * built from the same auto-configured {@link PrometheusConfig}, {@link PrometheusRegistry} and span context, so the
 * exposition and the scrape endpoint are the framework's.
 */
@Configuration(proxyBeanMethods = false)
class GaugeRefresherBeans {

    /** A reading stands this long before a scrape asks for another: the smallest floor any gauge class had (the outbox's). */
    static final Duration FLOOR = Duration.ofSeconds(5);

    /** {@code finapp.gauges.stale-after}'s default: how long an ask may go unanswered before every reading it waits on answers no value. */
    static final String STALE_AFTER = "PT2M";

    /** The canary: a constant read through the same path as every gauge, so no value exactly when this instance's refresher does not answer. */
    static final String REFRESHED = "finapp.gauges.refreshed";

    /**
     * One per instance. {@code finapp.gauges.read-at-start} (default true) is for the test contexts, where a background
     * reader would race what a suite asserts. Were it off in production, a gauge would read first at the first scrape, and a
     * NaN at a {@code delta} window's edge blinds that window's growth rules. {@code AccountingGaugeWiringTest} boots
     * production's own file and holds the default.
     */
    @Bean
    GaugeRefresher gaugeRefresher(@Value("${finapp.gauges.read-at-start:true}") boolean readAtStart,
            @Value("${finapp.gauges.stale-after:" + STALE_AFTER + "}") Duration staleAfter, Clock clock) {
        return new GaugeRefresher(readAtStart, clock, FLOOR, staleAfter);
    }

    @Bean
    PrometheusMeterRegistry prometheusMeterRegistry(PrometheusConfig prometheusConfig, PrometheusRegistry prometheusRegistry,
            io.micrometer.core.instrument.Clock micrometerClock, ObjectProvider<SpanContext> spanContext, GaugeRefresher gaugeRefresher) {
        return new OffScrapeMeterRegistry(prometheusConfig, prometheusRegistry, micrometerClock, spanContext.getIfAvailable(), gaugeRefresher);
    }

    @Bean
    GaugeRefresherLifecycle gaugeRefresherLifecycle(GaugeRefresher gaugeRefresher, MeterRegistry meterRegistry) {
        return new GaugeRefresherLifecycle(gaugeRefresher, meterRegistry);
    }

    /**
     * The refresher as a lifecycle bean, started before the web server's phase, so the first reads are asked for before
     * anything can scrape, and stopped after it; and its canary, {@value #REFRESHED}: 1, read through the refresher like
     * every {@code finapp.} gauge, so NaN before its first read and once an ask has waited {@code finapp.gauges.stale-after}
     * - every {@code finapp.} gauge of the instance is NaN with it then. {@code GaugesStale} alerts on it, per instance.
     */
    static final class GaugeRefresherLifecycle implements SmartLifecycle {

        private final GaugeRefresher refresher;

        GaugeRefresherLifecycle(GaugeRefresher refresher, MeterRegistry registry) {
            this.refresher = refresher;
            Gauge.builder(REFRESHED, () -> 1)
                    .description("1 while this instance's gauge refresher answers within finapp.gauges.stale-after; no value"
                            + " before its first read and once an ask has gone unanswered that long, when every finapp gauge of"
                            + " the instance is no value with it. Per instance: alert per instance, never aggregate")
                    .strongReference(true)
                    .register(registry);
        }

        @Override
        public void start() {
            refresher.start();
        }

        @Override
        public void stop() {
            refresher.stop();
        }

        @Override
        public boolean isRunning() {
            return refresher.isRunning();
        }

        /** Before the web server's phase, so the first reads are asked for before anything can scrape; stopped after it. */
        @Override
        public int getPhase() {
            return 0;
        }
    }
}
