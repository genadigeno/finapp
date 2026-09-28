package com.finapp.app.telemetry;

import com.finapp.merchant.PayoutDestinationStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes the open payout destination changes, as a gauge over the database (`P6-TSK-011`,
 * {@code PHASE_6_PLAN.md} §15: {@code finapp.merchant.destination.pending}).
 *
 * <p>{@code KycMetrics}' shape, for its reasons unchanged: read from the table, because the open
 * changes are a property of the fleet and a per-instance counter reports each replica's share of
 * a fact none of them owns; {@link Double#NaN} when unreadable, never zero, because a zero says
 * "no destination is changing" at the exact moment nothing can be known; cached behind a floor,
 * so a scrape does not become load on the database it is monitoring. Every instance reports the
 * same fleet-wide figure — dashboards aggregate with {@code max()}, never {@code sum()}.
 *
 * <p>What it counts is {@link PayoutDestinationStore#countOpen}'s subject: proposals awaiting a
 * second operator, and approvals still cooling off. A change that took effect, was rejected or
 * was withdrawn is finished, and a destination change nobody expected showing up here during its
 * cooling-off is exactly what the gauge exists to make visible.
 */
@Slf4j
final class MerchantMetrics {

    /** {@code finapp.merchant.destination.pending} — open destination changes, fleet-wide. */
    static final String DESTINATION_PENDING = "finapp.merchant.destination.pending";

    /** The {@code IdentityMetrics} floor, same argument: scrapes must not become queries. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** Where a connection comes from — the {@code IdentityMetrics.Connections} seam. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    private final PayoutDestinationStore<Connection> destinations;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    MerchantMetrics(
            PayoutDestinationStore<Connection> destinations,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.destinations = destinations;
        this.connections = connections;
        this.clock = clock;
        Gauge.builder(DESTINATION_PENDING, this, self -> self.reading().valueOrNaN())
                .description(
                        "Payout destination changes awaiting a second operator or still cooling"
                                + " off. Read from the database, so every instance reports the"
                                + " same fleet-wide figure: aggregate with max(), never sum()")
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
            fresh = new Cached(clock.instant(), destinations.countOpen(connection));
        } catch (Exception unreadable) {
            // Never the exception's message and never an identifier: this runs on every scrape
            // (INV-AUD-02, and plain operability - the IdentityMetrics reasoning).
            log.warn(
                    "Could not read the open payout destination changes; the gauge reports absent"
                            + " rather than zero: {}",
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
