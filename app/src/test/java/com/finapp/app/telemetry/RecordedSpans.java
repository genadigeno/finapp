package com.finapp.app.telemetry;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Captures the spans the application actually recorded, so a test can read them back.
 *
 * <p>The same argument as reading a real Logback appender in the correlation tests rather than
 * mocking a logger: a telemetry test that asserts a tracing call was made proves the call was
 * made, not that an operator would see anything. What matters here is the span as it leaves the
 * SDK — its name, its trace, and whether the correlation attribute survived onto it.
 *
 * <p>{@link SimpleSpanProcessor} rather than a batching one on purpose: a batch processor exports
 * on a timer, so a test would have to wait or flush, and a test that waits for telemetry is a test
 * that fails intermittently on a loaded machine.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordedSpans {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    @Bean
    SpanProcessor inMemorySpanProcessor(InMemorySpanExporter exporter) {
        return SimpleSpanProcessor.create(exporter);
    }
}
