package com.finapp.app.lifecycle;

import org.springframework.boot.web.server.context.WebServerApplicationContext;

/**
 * The phases in which a stopping instance lets go of its work (`P15-TSK-008`, ADR-0120 decision 4). Spring stops
 * {@code SmartLifecycle} beans in <strong>descending</strong> phase order, one phase at a time, waiting for each phase up to
 * {@code spring.lifecycle.timeout-per-shutdown-phase}; the pool is a bean destroyed after every phase has stopped.
 *
 * <p>What a stopping instance does, in the order Spring's own phases give it (held on a real context by
 * {@code ShutdownOrderTest}):
 *
 * <ol>
 *   <li>readiness turns {@code REFUSING_TRAFFIC} - Spring Boot publishes it as the context starts to close, before any
 *       phase stops;
 *   <li>{@link WebServerApplicationContext#GRACEFUL_SHUTDOWN_PHASE} (Boot's): the API port stops accepting and the requests
 *       it accepted finish ({@code server.shutdown: graceful});
 *   <li>{@link #SCHEDULES}: every schedule stops claiming at once and its tick in flight finishes within its bound
 *       ({@link ScheduleDrain}), all of them in parallel;
 *   <li>{@link #CONSUMERS}: every consumer loop takes no new batch, acknowledges the one it holds and leaves its group;
 *   <li>{@code GRACEFUL_SHUTDOWN_PHASE - 512} (Boot's): the management server closes. The probes are answered through every
 *       step above, which is why both of ours sit above it;
 *   <li>{@link WebServerApplicationContext#START_STOP_LIFECYCLE_PHASE} (Boot's): the API's web server stops, already drained;
 *   <li>phase {@code 0}: the gauge refresher ({@code platform}'s {@code GaugeRefresher}, in the context through
 *       {@code GaugeRefresherBeans}' lifecycle; {@code AccountingGaugeRefresher} until `P15-TSK-024` moved every gauge onto
 *       it) drops its queued reads and lets the one in flight end within its bound;
 *   <li>the beans are destroyed - the Kafka producer, and the connection pool after every stop above has returned.
 * </ol>
 *
 * <p><strong>Correctness never depends on any of this.</strong> A tick that outlives its bound is interrupted and its claim
 * is taken by another instance once its lease lapses or its transaction rolls back; a killed process skips every step. The
 * drain only means fewer retries.
 */
public final class ShutdownOrder {

    /** The schedules: after the HTTP drain, so readiness and the accepted requests go first; before the consumers. */
    public static final int SCHEDULES = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 128;

    /** The consumer loops: after the schedules, before the management server closes. */
    public static final int CONSUMERS = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 256;

    private ShutdownOrder() {
        // Constants only.
    }
}
