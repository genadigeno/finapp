package com.finapp.app.telemetry;

import com.finapp.identity.SessionStore;
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
 * Publishes how many sessions are live, as a gauge over the database (`P1-TSK-029`).
 *
 * <h2>Over the database, not over a counter this application keeps</h2>
 *
 * <p>{@code P0-TSK-029}'s reasoning for the outbox gauges, unchanged and just as decisive here. A
 * count held in the application is <strong>per instance</strong> — ten replicas would each report
 * their own share of something that is a property of the fleet — and it reports nothing when the
 * application is the thing that is wrong. The question "how many people are logged in" is a
 * question about the table.
 *
 * <h2>Live, not {@code ACTIVE} — and that distinction is the sharpest point of this class</h2>
 *
 * <p>ADR-0030 and {@code P1-TSK-013} decided there is no {@code EXPIRED} status and no sweep:
 * expiry is <em>derived</em> in the {@code WHERE} clause. So a gauge counting {@code status =
 * 'ACTIVE'} would count sessions <strong>nobody can use</strong> — and it would be wrong in the
 * reassuring direction, reporting live customers indefinitely while everyone had long since been
 * logged out. {@link SessionStore#countLive} uses {@code findLive}'s own predicate, so the gauge
 * and the lookup cannot disagree about what a session is.
 *
 * <h2>NaN, never zero</h2>
 *
 * <p>An unreadable database reports {@link Double#NaN}, which Prometheus records as absent. A zero
 * would say "nobody is logged in" at the exact moment nothing can be known, and an alert written
 * on a drop to zero would stay silent through the outage. Absent data is alertable; a comforting
 * zero is not.
 *
 * <h2>Ten instances all report the same number</h2>
 *
 * <p>Every replica reads the same table, so every replica publishes the same fleet-wide figure. A
 * dashboard must therefore use {@code max()} or {@code avg()}, <strong>never {@code sum()}</strong>
 * — summing ten identical readings reports ten times the truth. That is stated here, in the meter's
 * description, and in the dashboard panel, because it is the one way this gauge is easy to misread.
 */
@Slf4j
final class IdentityMetrics {

    /** {@code finapp.identity.session.active} — sessions that a request could actually use. */
    static final String ACTIVE_SESSIONS = "finapp.identity.session.active";

    /**
     * A scrape asks every gauge at once, and several scrapers may be attached. Without a floor,
     * monitoring becomes load on the database it is monitoring — {@code OutboxMetrics}' reasoning,
     * and it matters more here because this query has no partial index to lean on.
     */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /**
     * Where a connection comes from.
     *
     * <p>{@code OutboxBacklog}'s shape rather than a {@link javax.sql.DataSource}: this class needs
     * <em>a connection</em>, not a pool, and asking for the narrower thing is what lets its unit
     * test run without a database at all. {@code TestTaxonomyTest} is what surfaced that — it saw a
     * {@code DataSource} being asked for a connection and placed the test in the database tier,
     * correctly, because a detector cannot tell a reflective proxy from a pool and should not try.
     */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    private final SessionStore<Connection> sessions;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    IdentityMetrics(
            SessionStore<Connection> sessions,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.sessions = sessions;
        this.connections = connections;
        this.clock = clock;

        Gauge.builder(ACTIVE_SESSIONS, this, self -> self.reading().valueOrNaN())
                .description(
                        "Sessions that are live right now - ACTIVE and within both bounds. Read"
                                + " from the database, so every instance reports the same"
                                + " fleet-wide figure: aggregate with max(), never sum()")
                // No baseUnit. Micrometer APPENDS it to the Prometheus name, which is how
                // `baseUnit("events")` once published a series no dashboard queried (P0-TSK-029).
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
            fresh = new Cached(clock.instant(), sessions.countLive(connection, clock.instant()));
        } catch (Exception unreadable) {
            // Never the exception's message at INFO and never a session identifier: this runs on
            // every scrape, so a noisy failure would fill the log faster than anything a person
            // wrote (INV-AUD-02, and plain operability).
            log.warn(
                    "Could not read the live session count; the gauge reports absent rather than"
                            + " zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Cached.UNKNOWN);
        }
        cached.set(fresh);
        return fresh;
    }

    /**
     * One reading and when it was taken.
     *
     * <p>Non-authoritative and per instance, so it appears in no register in {@code
     * DISTRIBUTED_EXECUTION.md} §3: it decides nothing, and a stale reading is a stale
     * <em>reading</em>. An {@link AtomicReference} rather than a lock, because two instances
     * refreshing at once is two harmless queries, not a race.
     */
    private record Cached(Instant takenAt, long value) {

        /** Not a count. A negative sentinel cannot collide with one, which zero could. */
        static final long UNKNOWN = -1L;

        static Cached empty() {
            // Instant.MIN so the first read is always stale. NOT a nanoTime sentinel: the first
            // version of OutboxMetrics compared against Long.MIN_VALUE and the subtraction
            // OVERFLOWED, making both gauges structurally always NaN with two tests green over it.
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
