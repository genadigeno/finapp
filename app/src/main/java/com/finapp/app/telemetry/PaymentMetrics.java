package com.finapp.app.telemetry;

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
 * The stuck-payment gauges (`P5-TSK-017`, `PHASE_5_PLAN.md` §15):
 * {@code finapp.payments.unknown.active} and {@code finapp.payments.unknown.age}.
 *
 * <h2>What they are for</h2>
 *
 * <p>{@code INV-LIFE-03}'s operational face. An {@code *_UNKNOWN} payment operation is the
 * platform being honest about not knowing whether a provider acted — correct, and
 * <em>temporary by design</em>: the sweeper (`P5-TSK-014`) and the webhook resolver
 * (`P5-TSK-013`) settle it. A count that stays above zero, or an age that climbs, means
 * neither resolver is getting there — and for refunds it means a customer's funds are sitting
 * behind a standing hold nobody is releasing. That is the alert this phase exists to make
 * possible, and no other series can carry it.
 *
 * <p><strong>Attempts and refunds are one number, deliberately.</strong> The plan names one
 * gauge, and an operator asking "is money parked?" is not asking which machine parked it —
 * a split would need a tag nothing else wants and would let one half's alert be written and
 * the other half's forgotten. Which machine it is, is a query away in the database, where
 * identifiers are allowed to live.
 *
 * <h2>NaN, never zero</h2>
 *
 * <p>`P1-TSK-029`'s rule, and it bites exactly as hard here as on the trial balance: zero
 * means "nothing is stuck", so publishing zero when the database could not be read would
 * silence the alert at the precise moment the platform is least healthy. Unreadable reports
 * {@link Double#NaN} — absent, and alertable as absent.
 *
 * <h2>Fleet-wide, and floored</h2>
 *
 * <p>Every instance computes the same system-wide answer over the shared database, so a
 * dashboard aggregates with {@code max()}, <strong>never {@code sum()}</strong> (the
 * {@code LedgerMetrics} rule). The refresh floor is what keeps N instances from turning a
 * scrape interval into N queries per second: two indexed aggregates per floor period per
 * instance, and the cached reading is also the log's rate limit. Nothing is written, ever —
 * a gauge that healed what it found would destroy the evidence of the stall.
 */
final class PaymentMetrics {

    /** {@code finapp.payments.unknown.active} — operations in an honestly-unknown state. */
    static final String UNKNOWN_ACTIVE = "finapp.payments.unknown.active";

    /** {@code finapp.payments.unknown.age} — how long the oldest has been there, seconds. */
    static final String UNKNOWN_AGE = "finapp.payments.unknown.age";

    /** The cheap-read floor, the sibling gauges' own: two indexed aggregates, not a fold. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(PaymentMetrics.class);

    /** The {@code LedgerMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The store seam — so the unit test needs no database and no schema. */
    @FunctionalInterface
    interface Unknowns {
        Reading read(Connection connection);
    }

    /** One system-wide reading: how many are stuck, and how old the oldest is. */
    record Reading(long active, long oldestAgeSeconds) {}

    private final Unknowns attempts;
    private final Unknowns refunds;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    PaymentMetrics(
            Unknowns attempts,
            Unknowns refunds,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.attempts = attempts;
        this.refunds = refunds;
        this.connections = connections;
        this.clock = clock;

        // Eager (P1-TSK-029): a freshly started instance publishes both series, so the
        // stuck-payment alert has something to evaluate from the first scrape rather than
        // an absence that reads as a healthy quiet.
        Gauge.builder(UNKNOWN_ACTIVE, this, self -> self.reading().activeOrNaN())
                .description(
                        "Payment operations - attempts and refunds alike - sitting in an"
                                + " honestly-unknown state (INV-LIFE-03), system-wide. A"
                                + " count, never an amount. Temporary by design: a sustained"
                                + " non-zero value means neither the sweeper nor a webhook is"
                                + " resolving them, and a refund among them is a customer's"
                                + " money behind a standing hold. NaN when unreadable, never"
                                + " zero. Fleet-wide from every instance: aggregate with"
                                + " max(), never sum()")
                // No baseUnit (the P0-TSK-029 finding: Micrometer appends it to the name).
                .strongReference(true)
                .register(registry);

        Gauge.builder(UNKNOWN_AGE, this, self -> self.reading().ageOrNaN())
                .description(
                        "Seconds the OLDEST unknown payment operation has been unknown,"
                                + " measured from the state's own entry the way the sweeper"
                                + " measures it. The stuck-payment alert's series: a count"
                                + " that stays flat is ambiguity being resolved, an age that"
                                + " climbs is ambiguity that is not. NaN when unreadable,"
                                + " never zero. Fleet-wide: aggregate with max(), never"
                                + " sum()")
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
            Reading attemptSide = attempts.read(connection);
            Reading refundSide = refunds.read(connection);
            fresh =
                    new Cached(
                            clock.instant(),
                            attemptSide.active() + refundSide.active(),
                            Math.max(
                                    attemptSide.oldestAgeSeconds(),
                                    refundSide.oldestAgeSeconds()));
        } catch (SQLException | RuntimeException unreadable) {
            // NaN, never zero: an unreadable database must not be published as "nothing is
            // stuck". WARN once per refresh, not per scrape - the cache is the rate limit.
            log.warn(
                    "Could not read the unknown-payment gauges; they report absent rather"
                            + " than a false zero (INV-LIFE-03's alert must not be silenced)",
                    unreadable);
            fresh = Cached.unreadable(clock.instant());
        }
        cached.set(fresh);
        return fresh;
    }

    /**
     * One reading and when it was taken. Non-authoritative and per instance — the
     * {@code LedgerMetrics.Cached} stance, so no {@code DISTRIBUTED_EXECUTION.md} §3 row:
     * it decides nothing, and a stale reading is a stale <em>reading</em>.
     */
    private record Cached(Instant takenAt, long active, long oldestAgeSeconds) {

        /** Not a count and not an age. A negative sentinel cannot collide with either. */
        static final long UNKNOWN = -1L;

        static Cached empty() {
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
