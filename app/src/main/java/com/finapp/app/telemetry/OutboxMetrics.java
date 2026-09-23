package com.finapp.app.telemetry;

import com.finapp.platform.outbox.OutboxBacklog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes the outbox backlog as two gauges, from one reading.
 *
 * <p>ADR-0005's first two named metrics, and the debt recorded when the relay landed: {@code
 * RelayPollResult} was returned and aggregated nowhere, so a stalled aggregate was visible only by
 * reading logs — detection by reading rather than by alerting.
 *
 * <h2>One reading behind both gauges</h2>
 *
 * <p>Micrometer polls each gauge independently at scrape time. Two gauges each running their own
 * query would report depth and age from two different instants — a pair that can show zero pending
 * beside a non-zero age, a state that never existed and that an operator would reasonably spend an
 * hour trying to explain. Both read from one cached {@link OutboxBacklog.Reading}, refreshed at
 * most once per {@link #MIN_REFRESH_NANOS}.
 *
 * <p>The cache also bounds the cost. A scrape hits every gauge, several scrapers may be attached,
 * and this is a database query — an uncached gauge turns monitoring into load, which is the
 * failure where the observation causes the incident.
 *
 * <h2>What happens when the database is unreachable</h2>
 *
 * <p>The gauges report {@link Double#NaN}, which Prometheus records as absent rather than as zero.
 * That distinction is the whole design: a zero would say "the outbox is empty and all is well" at
 * the exact moment nothing can be known, and an alert written on {@code == 0} would stay silent
 * through the outage. Absent data is alertable; a comforting zero is not.
 */
@Slf4j
final class OutboxMetrics {

    /** {@code finapp.outbox.pending} — unpublished events, dead-lettered ones included. */
    static final String PENDING = "finapp.outbox.pending";

    /** {@code finapp.outbox.oldest} — how long the oldest unpublished event has waited. */
    static final String OLDEST = "finapp.outbox.oldest";

    /**
     * A scrape asks every gauge at once, and several scrapers may be attached. Without a floor,
     * monitoring becomes load on the database it is monitoring.
     */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    private final OutboxBacklog backlog;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    OutboxMetrics(OutboxBacklog backlog, Clock clock, MeterRegistry registry) {
        this.backlog = backlog;
        this.clock = clock;

        Gauge.builder(PENDING, this, self -> self.reading().pendingOrNaN())
                .description("Unpublished outbox events, including dead-lettered events blocking their aggregate")
                // No baseUnit. Micrometer APPENDS it to the Prometheus name, so `events` published
                // finapp_outbox_pending_events - which no dashboard queried and no test noticed,
                // because the assertion was a substring match that the longer name satisfies.
                // `seconds` on the gauge below is different: it is a real Prometheus base unit and
                // the suffix is the convention there.
                .strongReference(true)
                .register(registry);

        Gauge.builder(OLDEST, this, self -> self.reading().oldestSecondsOrNaN())
                .description("Age of the oldest unpublished outbox event")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    private Cached reading() {
        Instant now = clock.instant();
        Cached current = cached.get();
        if (!current.isStale(now)) {
            return current;
        }
        Cached refreshed;
        try {
            OutboxBacklog.Reading read = backlog.read();
            refreshed = new Cached(now, read.pending(), read.oldest().toSeconds(), true);
        } catch (RuntimeException failure) {
            // Logged at warn and reported as absent, not as zero. The exception type only: a JDBC
            // message names the host, the database and sometimes the user (INV-AUD-02).
            log.warn("Could not read the outbox backlog for metrics: {}", failure.getClass().getSimpleName());
            refreshed = new Cached(now, 0, 0, false);
        }
        // A losing racer discards its own equally-fresh reading. Both are correct; neither is
        // worth a lock on a scrape path.
        cached.compareAndSet(current, refreshed);
        return refreshed;
    }

    /**
     * @param takenAt when this reading was taken, or {@code null} before the first one. Null
     *     rather than a sentinel timestamp: the first attempt used {@code Long.MIN_VALUE} against
     *     {@code System.nanoTime()}, and the subtraction <strong>overflowed</strong> to a negative
     *     number, so the empty cache was never stale, never refreshed, and both gauges published
     *     NaN for ever. Two tests were green over it - one asserting NaN when the database is
     *     absent, which an always-NaN gauge satisfies perfectly, and one exercising the query
     *     rather than the gauge. It took scraping a running instance.
     */
    private record Cached(Instant takenAt, long pending, long oldestSeconds, boolean known) {

        static Cached empty() {
            return new Cached(null, 0, 0, false);
        }

        boolean isStale(Instant now) {
            return takenAt == null || !now.isBefore(takenAt.plus(MIN_REFRESH));
        }

        double pendingOrNaN() {
            return known ? pending : Double.NaN;
        }

        double oldestSecondsOrNaN() {
            return known ? oldestSeconds : Double.NaN;
        }
    }
}
