package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.platform.clock.offset} (`P15-TSK-025`; ADR-0063's Follow-up): this instance's clock minus the database's,
 * in seconds, positive when this instance runs ahead.
 *
 * <p>ADR-0063 clamps a business stamp that would contradict the order of facts, so a stepped clock no longer surfaces as a
 * refused write. This is the signal that replaces the one the clamp removed. Coordination never reads it (ADR-0014):
 * every lease and permit is the database's own clock, so an offset costs correctness nothing and the alert is a ticket.
 *
 * <p><strong>Per instance, never aggregated</strong>: each instance publishes its own clock, and {@code max()} over a fleet
 * would hide one running behind. The midpoint of the query's two readings stands for the instant the database answered, so
 * the round trip does not read as skew. NaN when unreadable, never zero: a zero says the clocks agree.
 */
@Slf4j
final class ClockOffsetMetrics {

    /** Exported as {@code finapp_platform_clock_offset_seconds}. */
    static final String OFFSET = "finapp.platform.clock.offset";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(30);

    /** The seam, for the unit test: the {@code IdentityMetrics.Connections} precedent. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(null, Double.NaN));

    ClockOffsetMetrics(Connections connections, Clock clock, MeterRegistry registry) {
        this.connections = connections;
        this.clock = clock;
        Gauge.builder(OFFSET, this, ClockOffsetMetrics::seconds)
                .description("This instance's clock minus the database's, in seconds; positive when it runs ahead, NaN"
                        + " when unreadable. Per instance: never aggregated")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    private double seconds() {
        Instant now = clock.instant();
        Cached current = cached.get();
        if (current.takenAt() != null && now.isBefore(current.takenAt().plus(MIN_REFRESH))) {
            return current.seconds();
        }
        Cached fresh = new Cached(now, read());
        cached.compareAndSet(current, fresh);
        return fresh.seconds();
    }

    private double read() {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = select.executeQuery()) {
            Instant before = clock.instant();
            row.next();
            Instant database = row.getObject(1, OffsetDateTime.class).toInstant();
            Instant after = clock.instant();
            Instant midpoint = before.plus(Duration.between(before, after).dividedBy(2));
            return Duration.between(database, midpoint).toMillis() / 1000.0;
        } catch (SQLException | RuntimeException failure) {
            // The class only: a JDBC message can name the host, the database and the user (INV-AUD-02).
            log.warn("The instance's clock offset could not be read: {}", failure.getClass().getSimpleName());
            return Double.NaN;
        }
    }

    private record Cached(Instant takenAt, double seconds) {}
}
