package com.finapp.app.telemetry;

import com.finapp.app.lifecycle.ShutdownOrder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

/**
 * The OpenTelemetry SDK's last flush, bounded (`P15-TSK-023`'s review): the spans and log records still queued are handed to
 * the exporter once the work has stopped, and the stop waits for that at most {@link #BOUND}.
 *
 * <p><strong>Why a bound of its own.</strong> Left to the beans' destroy, each provider closes as {@code shutdown().join(10 s)},
 * and an export to a collector that accepts and never answers takes its call timeout and then the exporter's retries, so a
 * rollout during a hung collector would spend the pod's grace there and be killed in the middle of closing the producer and
 * the pools. Here the providers are shut down together in the ordered stop, after every schedule, consumer, web server and the
 * gauge refresher and before the beans are destroyed, and the stop gives up after the bound: what the exporter has not sent
 * by then is lost, which is telemetry and never money. Their later {@code close()} finds them already shut down and returns at
 * once. The bound is a row of {@code infra/scripts/manifest-policy/shutdown-budget.yaml}, held to this constant by
 * {@code KubernetesManifestsTest}; {@code TelemetryFlushTest} holds it against an exporter that never answers.
 */
@Slf4j
public final class TelemetryFlush implements SmartLifecycle {

    /** The longest the stop waits for the SDK's last export. */
    public static final Duration BOUND = Duration.ofSeconds(2);

    private final List<Supplier<CompletableResultCode>> shutdowns;
    private volatile boolean running;

    TelemetryFlush(List<Supplier<CompletableResultCode>> shutdowns) {
        this.shutdowns = List.copyOf(Objects.requireNonNull(shutdowns, "shutdowns"));
    }

    /** Every provider the context holds, the traces', the logs' and the metrics'. */
    static TelemetryFlush of(ObjectProvider<SdkTracerProvider> traces, ObjectProvider<SdkLoggerProvider> logs,
            ObjectProvider<SdkMeterProvider> metrics) {
        List<Supplier<CompletableResultCode>> shutdowns = new ArrayList<>();
        traces.ifAvailable(provider -> shutdowns.add(provider::shutdown));
        logs.ifAvailable(provider -> shutdowns.add(provider::shutdown));
        metrics.ifAvailable(provider -> shutdowns.add(provider::shutdown));
        return new TelemetryFlush(shutdowns);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        CompletableResultCode all = CompletableResultCode.ofAll(shutdowns.stream().map(Supplier::get).toList());
        all.join(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        if (!all.isDone()) {
            log.warn("The telemetry SDK's last export did not finish within {}: what it still held is dropped", BOUND);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The longest {@link #stop()} takes: the context's lifecycle reads it ({@code ShutdownOrderTest}). */
    public Duration stopBound() {
        return BOUND;
    }

    /** Last of the platform's components to stop, before the beans - the producer and the pools - are destroyed. */
    @Override
    public int getPhase() {
        return ShutdownOrder.TELEMETRY_FLUSH;
    }
}
