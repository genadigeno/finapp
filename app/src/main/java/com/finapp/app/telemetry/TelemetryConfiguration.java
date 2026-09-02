package com.finapp.app.telemetry;

import io.micrometer.tracing.Tracer;
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
