package com.finapp.app.telemetry;

import com.finapp.payments.PaymentAttemptStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * A stuck-operation gauge pair (`P7-TSK-015`, {@code PHASE_7_PLAN.md} §15) —
 * {@code MerchantPayoutMetrics}' shape verbatim, parameterized, because Phase 7 adds two more
 * machines with a modelled unknown and a sweep that resolves it: the withdrawal
 * ({@code finapp.payments.withdrawal.unknown.*}) and the dispute answer
 * ({@code finapp.payments.dispute.response.unknown.*}, {@code INV-LIFE-03}'s own "unknown-state
 * age metric"). One implementation rather than two more copies; the existing pairs are left as
 * they are — not this task's to refactor.
 *
 * <h2>What a pair says</h2>
 *
 * <p>{@code .active} counts every operation the platform has no answer for past the point one was
 * due — every {@code UNKNOWN}, and every {@code DISPATCHED} one whose send permit is older than the
 * sweep's own bound (the reading's store method shares the sweep's one placeholder, so the two can
 * never disagree about when an answer was due). {@code .age} is how long the oldest has waited. A
 * count that stays above zero, or an age that climbs, means the sweep is not getting there — the
 * stuck-operation alert, which no other series can carry.
 *
 * <h2>NaN, never zero; fleet-wide; floored</h2>
 *
 * <p>Zero means "nothing is stuck", so publishing zero when the database could not be read would
 * silence the alert at the moment the platform is least healthy: unreadable reports
 * {@link Double#NaN}. Every instance computes the same system-wide answer over the shared
 * database — aggregate with {@code max()}, never {@code sum()} — and the refresh floor keeps N
 * instances from turning a scrape interval into N queries per second; the cached reading is also
 * the log's rate limit. Nothing is written, ever. Per instance and non-authoritative: it decides
 * nothing, so it needs no {@code DISTRIBUTED_EXECUTION.md} §3 row.
 */
@Slf4j
final class StuckOperationMetrics {

    /** The sibling gauges' cheap-read floor: one indexed aggregate, not a fold. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code PaymentMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The store seam — so the unit test needs no database and no schema. */
    @FunctionalInterface
    interface Unknowns {
        PaymentAttemptStore.UnknownReading read(Connection connection);
    }

    /** The pair's names and what each says — the operator's description, per machine. */
    record Series(String activeName, String activeDescription, String ageName, String ageDescription) {

        Series {
            Objects.requireNonNull(activeName, "activeName must not be null");
            Objects.requireNonNull(activeDescription, "activeDescription must not be null");
            Objects.requireNonNull(ageName, "ageName must not be null");
            Objects.requireNonNull(ageDescription, "ageDescription must not be null");
        }
    }

    private final String what;
    private final Unknowns unknowns;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    /**
     * Registers both gauges eagerly (`P1-TSK-029`): a freshly started instance publishes the pair,
     * so the alert evaluates something from the first scrape rather than an absence that reads as
     * a healthy quiet.
     *
     * @param what the machine's name for the log line, e.g. {@code "stuck-withdrawal"}
     */
    StuckOperationMetrics(
            String what,
            Series series,
            Unknowns unknowns,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.what = Objects.requireNonNull(what, "what must not be null");
        Objects.requireNonNull(series, "series must not be null");
        this.unknowns = Objects.requireNonNull(unknowns, "unknowns must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Gauge.builder(series.activeName(), this, self -> self.reading().activeOrNaN())
                .description(series.activeDescription())
                // No baseUnit (the P0-TSK-029 finding: Micrometer appends it to the name).
                .strongReference(true)
                .register(registry);
        Gauge.builder(series.ageName(), this, self -> self.reading().ageOrNaN())
                .description(series.ageDescription())
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
            PaymentAttemptStore.UnknownReading read = unknowns.read(connection);
            fresh = new Cached(clock.instant(), read.active(), read.oldestAgeSeconds());
        } catch (SQLException | RuntimeException unreadable) {
            // NaN, never zero. The class only - a JDBC message can name hosts and values - and
            // once per refresh, not per scrape: the cache is the rate limit.
            log.warn(
                    "Could not read the {} gauges; they report absent rather than a false zero:"
                            + " {}",
                    what,
                    unreadable.getClass().getSimpleName());
            fresh = Cached.unreadable(clock.instant());
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken — the {@code MerchantPayoutMetrics.Cached} stance. */
    private record Cached(Instant takenAt, long active, long oldestAgeSeconds) {

        /** Not a count and not an age. A negative sentinel cannot collide with either. */
        static final long UNKNOWN = -1L;

        static Cached empty() {
            // Instant.MIN so the first read is always stale (the OutboxMetrics overflow lesson).
            return new Cached(Instant.MIN, UNKNOWN, UNKNOWN);
        }

        static Cached unreadable(Instant at) {
            return new Cached(at, UNKNOWN, UNKNOWN);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double activeOrNaN() {
            return active == UNKNOWN ? Double.NaN : (double) active;
        }

        double ageOrNaN() {
            return oldestAgeSeconds == UNKNOWN ? Double.NaN : (double) oldestAgeSeconds;
        }
    }
}
