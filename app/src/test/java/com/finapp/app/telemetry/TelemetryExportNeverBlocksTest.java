package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Export never blocks a request (`P15-TSK-023`, ADR-0124 decision 1; the entry's failure line): with Boot's own tracing and logging
 * export pointed at a collector that accepts a connection and never answers - the worst of down, slow and refusing, because nothing
 * ever times out quickly - a request thread's spans and log lines cost what they cost with no collector at all. The batch
 * processors take the work on their own threads behind bounded queues; this measures that the request's thread never waits.
 *
 * <p>The control is the stand-in's own count: the exporter really dialled it, so the timing below is not an exporter that was never
 * built. And readiness reads no exporter: no health contributor of this context is a telemetry one.
 *
 * <p>Its probe ({@code MUTATION_TESTING.md}): a span processor that exports each span on the thread that ends it, planted beside
 * the batch processor, makes this red.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
@DisplayName("a collector that never answers costs a request thread nothing: export is queued, never awaited (P15-TSK-023)")
class TelemetryExportNeverBlocksTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryExportNeverBlocksTest.class);

    /** Two hundred of each, far above one request's worth. */
    private static final int WORK = 200;

    /** The export call's own timeout is five seconds: one span waiting on it would take longer than all of this. */
    private static final Duration BUDGET = Duration.ofSeconds(3);

    private static final OtlpCapture.Hanging COLLECTOR = new OtlpCapture.Hanging();

    @Autowired private Tracer tracer;
    @Autowired private SdkTracerProvider tracers;
    @Autowired private ApplicationContext context;

    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) {
        registry.add("finapp.telemetry.traces-endpoint", () -> COLLECTOR.endpoint("/v1/traces"));
        registry.add("finapp.telemetry.logs-endpoint", () -> COLLECTOR.endpoint("/v1/logs"));
        // A batch as soon as there is anything, so the exporter dials the stand-in during the measurement and not after it.
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "10ms");
        registry.add("management.opentelemetry.tracing.export.max-batch-size", () -> "8");
    }

    @AfterAll
    static void stop() throws IOException {
        COLLECTOR.close();
    }

    @Test
    @DisplayName("two hundred spans ended and two hundred lines logged on the request's thread finish inside three seconds while the collector hangs")
    @SuppressWarnings("try") // The scope is used for its close side effect.
    void aHangingCollectorCostsTheRequestNothing() throws Exception {
        long started = System.nanoTime();
        int done = 0;
        // Stops at the budget rather than waiting out a blocked export two hundred times: the verdict below is the same.
        while (done < WORK && System.nanoTime() - started < BUDGET.toNanos()) {
            Span span = tracer.nextSpan().name("finapp.export.probe").start();
            try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                log.info("a request's line {}", done);
            } finally {
                span.end();
            }
            done++;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // The control: the exporter is real and it really dialled the stand-in, which is still holding it.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (COLLECTOR.accepted() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(COLLECTOR.accepted()).as("the exporter dialled the hanging collector").isPositive();
        assertThat(done).as("every span and line was done before the budget ran out").isEqualTo(WORK);
        assertThat(elapsed).as("the request's thread never waited on the collector").isLessThan(BUDGET);
        log.info("export-never-blocks: {} spans and lines in {} ms ({})", WORK, elapsed.toMillis(), UUID.randomUUID());
    }

    @Test
    @DisplayName("readiness reads no exporter: no health contributor of the context is a telemetry one")
    void readinessReadsNoExporter() {
        Map<String, HealthContributor> contributors = context.getBeansOfType(HealthContributor.class);
        assertThat(contributors).as("the context's health contributors were read").isNotEmpty();
        assertThat(contributors.keySet()).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).matches(".*(otlp|opentelemetry|telemetry|collector|tracing).*"));
        assertThat(tracers).isNotNull();
    }
}
