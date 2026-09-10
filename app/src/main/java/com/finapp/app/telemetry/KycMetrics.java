package com.finapp.app.telemetry;

import com.finapp.kyc.ReviewTaskStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the manual-review queue depth, as a gauge over the database (`P2-TSK-010`,
 * `PHASE_2_PLAN.md` §10: <em>the queue nobody watches is the queue that ages</em>).
 *
 * <p>{@code IdentityMetrics}' shape, for its reasons unchanged: read from the table, because the
 * queue is a property of the fleet and a per-instance counter reports each replica's share of a
 * fact none of them owns; {@link Double#NaN} when unreadable, never zero, because a zero says
 * "nobody is waiting" at the exact moment nothing can be known; cached behind a floor, so a
 * scrape does not become load on the database it is monitoring. Every instance reports the same
 * fleet-wide figure — dashboards aggregate with {@code max()}, never {@code sum()}.
 *
 * <p>What it counts is {@link ReviewTaskStore#countOpen}'s subject: {@code OPEN} tasks only. A
 * resolved task is finished work, and a gauge that counted it would report a queue that never
 * drains — the count becomes distinguishable from "all statuses" the day `P2-TSK-012` writes the
 * first resolution.
 */
final class KycMetrics {

    /** {@code finapp.kyc.review.queue} — checks awaiting a person, fleet-wide. */
    static final String REVIEW_QUEUE = "finapp.kyc.review.queue";

    /** The {@code IdentityMetrics} floor, same argument: scrapes must not become queries. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(KycMetrics.class);

    /** Where a connection comes from — the {@code IdentityMetrics.Connections} seam. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    private final ReviewTaskStore<Connection> reviewTasks;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    KycMetrics(
            ReviewTaskStore<Connection> reviewTasks,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.reviewTasks = reviewTasks;
        this.connections = connections;
        this.clock = clock;

        Gauge.builder(REVIEW_QUEUE, this, self -> self.reading().valueOrNaN())
                .description(
                        "Review tasks awaiting a person (OPEN). Read from the database, so every"
                                + " instance reports the same fleet-wide figure: aggregate with"
                                + " max(), never sum()")
                // No baseUnit - the P0-TSK-029 finding: Micrometer APPENDS it to the name.
                .strongReference(true)
                .register(registry);
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            fresh = new Cached(clock.instant(), reviewTasks.countOpen(connection));
        } catch (Exception unreadable) {
            // Never the exception's message and never an identifier: this runs on every scrape
            // (INV-AUD-02, and plain operability - the IdentityMetrics reasoning).
            log.warn(
                    "Could not read the review queue depth; the gauge reports absent rather than"
                            + " zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Cached.UNKNOWN);
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken - non-authoritative, per instance, decides nothing. */
    private record Cached(Instant takenAt, long value) {

        /** Not a count. A negative sentinel cannot collide with one, which zero could. */
        static final long UNKNOWN = -1L;

        static Cached empty() {
            // Instant.MIN so the first read is always stale (the OutboxMetrics overflow lesson).
            return new Cached(Instant.MIN, UNKNOWN);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double valueOrNaN() {
            return value == UNKNOWN ? Double.NaN : (double) value;
        }
    }
}
