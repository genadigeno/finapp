package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SDK's last export at a stop is bounded (`P15-TSK-023`'s review): with exporters that take every batch and never answer -
 * a collector that accepts and hangs, which no timeout of the exporter's shortens below the pod's slack - the ordered stop
 * returns within {@link TelemetryFlush#BOUND}, and the providers' own {@code close()} after it returns at once.
 *
 * <p>The control is the exporters' own count: the stop really handed them the queued span and record, so the timing is not
 * an export that never started. Its probe ({@code MUTATION_TESTING.md}): the stop joined without a bound goes red.
 */
@DisplayName("a stopping instance waits for the telemetry SDK's last export no longer than its bound, however the collector hangs (P15-TSK-023)")
class TelemetryFlushTest {

    @Test
    @DisplayName("exporters that never answer hold the stop no longer than the bound plus a second, and the providers' close then returns at once")
    void aHungExporterDoesNotHoldTheStop() {
        HungSpans spans = new HungSpans();
        HungRecords records = new HungRecords();
        // A batch only when asked, so the one span and the one record are still queued when the stop asks for the last export.
        SdkTracerProvider tracers = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spans).setScheduleDelay(Duration.ofHours(1)).build())
                .build();
        SdkLoggerProvider loggers = SdkLoggerProvider.builder()
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(records).setScheduleDelay(Duration.ofHours(1)).build())
                .build();
        Tracer tracer = tracers.get("flush-test");
        tracer.spanBuilder("queued").startSpan().end();
        Logger logger = loggers.get("flush-test");
        logger.logRecordBuilder().setBody("queued").emit();

        TelemetryFlush flush = new TelemetryFlush(List.of(tracers::shutdown, loggers::shutdown));
        flush.start();
        long started = System.nanoTime();
        flush.stop();
        Duration stopping = Duration.ofNanos(System.nanoTime() - started);

        started = System.nanoTime();
        tracers.close();
        loggers.close();
        Duration closing = Duration.ofNanos(System.nanoTime() - started);

        assertThat(spans.exported.get()).as("the stop handed the queued span to the exporter").isPositive();
        assertThat(records.exported.get()).as("the stop handed the queued record to the exporter").isPositive();
        assertThat(stopping).as("the stop gave up at its bound").isLessThan(TelemetryFlush.BOUND.plusSeconds(1));
        assertThat(closing).as("the providers' close found them shut down").isLessThan(Duration.ofSeconds(1));
        assertThat(flush.isRunning()).isFalse();
    }

    @Test
    @DisplayName("the flush is the last of the platform's stops: below the gauge refresher's phase 0, and its bound is the budget's")
    void itStopsLastWithItsOwnBound() {
        TelemetryFlush flush = new TelemetryFlush(List.of());
        assertThat(flush.getPhase()).isLessThan(0);
        assertThat(flush.stopBound()).isEqualTo(TelemetryFlush.BOUND).isLessThanOrEqualTo(Duration.ofSeconds(3));
        flush.stop();
    }

    /** Takes every batch and never answers, an export and a shutdown alike. */
    private static final class HungSpans implements SpanExporter {
        final AtomicInteger exported = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAndGet(spans.size());
            return new CompletableResultCode();
        }

        @Override
        public CompletableResultCode flush() {
            return new CompletableResultCode();
        }

        @Override
        public CompletableResultCode shutdown() {
            return new CompletableResultCode();
        }
    }

    /** As {@link HungSpans}, for log records. */
    private static final class HungRecords implements LogRecordExporter {
        final AtomicInteger exported = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            exported.addAndGet(logs.size());
            return new CompletableResultCode();
        }

        @Override
        public CompletableResultCode flush() {
            return new CompletableResultCode();
        }

        @Override
        public CompletableResultCode shutdown() {
            return new CompletableResultCode();
        }
    }
}
