package com.finapp.app.telemetry;

import com.finapp.merchant.MerchantPayoutStore;
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
 * The stuck-payout gauges (`P6-TSK-013`, {@code PHASE_6_PLAN.md} §15):
 * {@code finapp.merchant.payout.unknown.active} and {@code finapp.merchant.payout.unknown.age}.
 *
 * <h2>What they are for</h2>
 *
 * <p>A payout the platform has no answer for is a merchant's money reserved behind a standing
 * hold ({@code INV-LIFE-03}, ADR-0051's standing-hold doctrine) — correct, and temporary by
 * design, because the resolution sweep settles it. A count that stays above zero, or an age
 * that climbs, means nothing is getting there. That is the stuck-payout alert, and no other
 * series can carry it.
 *
 * <h2>Why "unknown" includes an overdue dispatch</h2>
 *
 * <p>{@code PaymentMetrics} counts only the honestly-unknown states, and its database suite says
 * why: a dispatched operation is mid-question, and counting it would alert on healthy traffic.
 * That holds within the sweep's bound and not past it. When the sweep is not running — the
 * failure this alert exists for — a dispatch whose instance crashed stays {@code DISPATCHED}
 * for ever, its merchant's money held, and an unknown-only gauge reads zero. So the reading is
 * the sweep's own candidacy with the {@code UNKNOWN} bound at zero:
 * {@link MerchantPayoutStore#unknownReading} counts every {@code UNKNOWN} payout and every
 * {@code DISPATCHED} one whose send permit is older than the sweep's dispatched bound — the
 * same configured bound, read through one placeholder, so the two can never disagree about
 * when an answer was due. Within it, a dispatch is still mid-question and counts nothing.
 *
 * <h2>NaN, never zero</h2>
 *
 * <p>`P1-TSK-029`'s rule: zero means "nothing is stuck", so publishing zero when the database
 * could not be read would silence the alert at the moment the platform is least healthy.
 * Unreadable reports {@link Double#NaN} — absent, and alertable as absent.
 *
 * <h2>Fleet-wide, and floored</h2>
 *
 * <p>Every instance computes the same system-wide answer over the shared database, so a
 * dashboard aggregates with {@code max()}, <strong>never {@code sum()}</strong>. The refresh
 * floor keeps N instances from turning a scrape interval into N queries per second, and the
 * cached reading is also the log's rate limit. Nothing is written, ever: a gauge that healed
 * what it found would destroy the evidence of the stall.
 */
@Slf4j
final class MerchantPayoutMetrics {

    /** {@code finapp.merchant.payout.unknown.active} — payouts past their due, unanswered. */
    static final String UNKNOWN_ACTIVE = "finapp.merchant.payout.unknown.active";

    /** {@code finapp.merchant.payout.unknown.age} — how long the oldest has waited, seconds. */
    static final String UNKNOWN_AGE = "finapp.merchant.payout.unknown.age";

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
        MerchantPayoutStore.UnknownReading read(Connection connection);
    }

    private final Unknowns payouts;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    MerchantPayoutMetrics(
            Unknowns payouts, Connections connections, Clock clock, MeterRegistry registry) {
        this.payouts = payouts;
        this.connections = connections;
        this.clock = clock;

        // Eager (P1-TSK-029): a freshly started instance publishes both series, so the
        // stuck-payout alert evaluates something from the first scrape rather than an absence
        // that reads as a healthy quiet.
        Gauge.builder(UNKNOWN_ACTIVE, this, self -> self.reading().activeOrNaN())
                .description(
                        "Merchant payouts the platform has no answer for past the point one was"
                                + " due: every UNKNOWN payout, and every DISPATCHED one whose"
                                + " send permit is older than the resolution sweep's own bound."
                                + " Each is a merchant's money behind a standing hold. A count,"
                                + " never an amount. NaN when unreadable, never zero."
                                + " Fleet-wide from every instance: aggregate with max(), never"
                                + " sum()")
                // No baseUnit (the P0-TSK-029 finding: Micrometer appends it to the name).
                .strongReference(true)
                .register(registry);

        Gauge.builder(UNKNOWN_AGE, this, self -> self.reading().ageOrNaN())
                .description(
                        "Seconds the OLDEST unanswered payout has waited, measured the way the"
                                + " resolution sweep measures it: an UNKNOWN payout from its"
                                + " entry into that state, an overdue DISPATCHED one from its"
                                + " latest send permit. The stuck-payout alert's series. NaN"
                                + " when unreadable, never zero. Fleet-wide: aggregate with"
                                + " max(), never sum()")
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
            MerchantPayoutStore.UnknownReading read = payouts.read(connection);
            fresh = new Cached(clock.instant(), read.active(), read.oldestAgeSeconds());
        } catch (SQLException | RuntimeException unreadable) {
            // NaN, never zero: an unreadable database must not be published as "nothing is
            // stuck". The class only - a JDBC message can name hosts and values - and once per
            // refresh, not per scrape: the cache is the rate limit.
            log.warn(
                    "Could not read the stuck-payout gauges; they report absent rather than a"
                            + " false zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = Cached.unreadable(clock.instant());
        }
        cached.set(fresh);
        return fresh;
    }

    /**
     * One reading and when it was taken. Non-authoritative and per instance — the
     * {@code PaymentMetrics.Cached} stance, so no {@code DISTRIBUTED_EXECUTION.md} §3 row: it
     * decides nothing, and a stale reading is a stale <em>reading</em>.
     */
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
