package com.finapp.app.telemetry;

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
 * The pay-by-bank gauges (`P7-TSK-009`, ADR-0062 §5's operational impact) — the
 * {@code PaymentMetrics} stance verbatim, on the two facts that class deliberately
 * excludes:
 *
 * <ul>
 *   <li><strong>the initiation age</strong> ({@code finapp.payments.payin.awaiting} /
 *       {@code .age}): a payer still deciding is the payer PSP's clock, not a platform
 *       fault, which is exactly why {@code AWAITING_PAYER} is absent from the
 *       stuck-payment gauges — and why its OWN ageing must be visible somewhere, because
 *       "never concluded by our clock" ({@code INV-LIFE-03}) is only honest when an
 *       operator can see what is quietly getting old;
 *   <li><strong>the suspense parkings</strong> ({@code finapp.payments.unmatched.active} /
 *       {@code .age}): every confirmation EVER parked, and the oldest one's age — payments'
 *       own table, measured truthfully: a parking has no resolved state, because the
 *       disposition belongs to its suspense item. Corrected by `P8-TSK-020` (ADR-0070 point
 *       8): these read as a standing alert, which a parking resolved by a person would never
 *       clear; the alertable signal is reconciliation's {@code finapp.reconciliation.suspense.*},
 *       which counts the parkings' items since that task. The names stay, so the Phase 7
 *       plan's rows still parse.
 * </ul>
 *
 * <p>NaN when unreadable, never zero; fleet-wide readings aggregated with {@code max()},
 * never {@code sum()}; the refresh floor is the rate limit ({@code PaymentMetrics} says
 * why, at length).
 */
@Slf4j
final class PayInMetrics {

    /** {@code finapp.payments.payin.awaiting} — initiations awaiting the payer, count. */
    static final String AWAITING_ACTIVE = "finapp.payments.payin.awaiting";

    /** {@code finapp.payments.payin.awaiting.age} — the oldest wait, seconds from birth. */
    static final String AWAITING_AGE = "finapp.payments.payin.awaiting.age";

    /** {@code finapp.payments.unmatched.active} — confirmations parked in suspense, ever. */
    static final String UNMATCHED_ACTIVE = "finapp.payments.unmatched.active";

    /** {@code finapp.payments.unmatched.age} — the oldest parking's age, ever, seconds. */
    static final String UNMATCHED_AGE = "finapp.payments.unmatched.age";

    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code PaymentMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The store seam — so the unit test needs no database and no schema. */
    @FunctionalInterface
    interface Subjects {
        Reading read(Connection connection);
    }

    /** One system-wide reading: a count, and how old the oldest is. */
    record Reading(long active, long oldestAgeSeconds) {}

    private final Subjects awaiting;
    private final Subjects parked;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    PayInMetrics(
            Subjects awaiting,
            Subjects parked,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.awaiting = awaiting;
        this.parked = parked;
        this.connections = connections;
        this.clock = clock;

        // Eager (P1-TSK-029): both alert series exist from the first scrape.
        Gauge.builder(AWAITING_ACTIVE, this, self -> self.reading().awaitingOrNaN())
                .description(
                        "Pay-by-bank initiations awaiting the payer's authorization at their"
                                + " own PSP, system-wide - a count, never an amount. Not a"
                                + " fault by itself (the payer PSP's clock decides,"
                                + " INV-LIFE-03), but the series an operator reads when"
                                + " asking what is quietly getting old. NaN when unreadable,"
                                + " never zero. Fleet-wide: aggregate with max(), never"
                                + " sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(AWAITING_AGE, this, self -> self.reading().awaitingAgeOrNaN())
                .description(
                        "Seconds the OLDEST awaiting pay-by-bank initiation has waited,"
                                + " measured from its birth - a permit renewal never makes"
                                + " an old wait look young. NaN when unreadable, never zero."
                                + " Fleet-wide: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(UNMATCHED_ACTIVE, this, self -> self.reading().parkedOrNaN())
                .description(
                        "Confirmations EVER parked in SUSPENSE_UNMATCHED with money the"
                                + " platform could not attribute (INV-REC-05) - payments'"
                                + " own table, which a resolution never shrinks. The"
                                + " alertable signal is finapp.reconciliation.suspense.*,"
                                + " which owns each parking's value since P8-TSK-020. NaN"
                                + " when unreadable, never zero. Fleet-wide: aggregate with"
                                + " max(), never sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(UNMATCHED_AGE, this, self -> self.reading().parkedAgeOrNaN())
                .description(
                        "Seconds since the OLDEST confirmation EVER parked - payments' own"
                                + " table, which a resolution never clears. Whether parked"
                                + " value is still waiting is"
                                + " finapp.reconciliation.suspense.age (P8-TSK-020). NaN when"
                                + " unreadable, never zero. Fleet-wide: aggregate with"
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
            Reading awaitingSide = awaiting.read(connection);
            Reading parkedSide = parked.read(connection);
            fresh =
                    new Cached(
                            clock.instant(),
                            awaitingSide.active(),
                            awaitingSide.oldestAgeSeconds(),
                            parkedSide.active(),
                            parkedSide.oldestAgeSeconds());
        } catch (SQLException | RuntimeException unreadable) {
            log.warn(
                    "Could not read the pay-in gauges; they report absent rather than a"
                            + " false zero (INV-REC-05's alert must not be silenced)",
                    unreadable);
            fresh = Cached.unreadable(clock.instant());
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken — per instance, non-authoritative. */
    private record Cached(
            Instant takenAt,
            long awaiting,
            long awaitingAge,
            long parked,
            long parkedAge) {

        static final long UNKNOWN = -1L;

        static Cached empty() {
            return new Cached(Instant.MIN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }

        static Cached unreadable(Instant at) {
            return new Cached(at, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double awaitingOrNaN() {
            return awaiting == UNKNOWN ? Double.NaN : (double) awaiting;
        }

        double awaitingAgeOrNaN() {
            return awaitingAge == UNKNOWN ? Double.NaN : (double) awaitingAge;
        }

        double parkedOrNaN() {
            return parked == UNKNOWN ? Double.NaN : (double) parked;
        }

        double parkedAgeOrNaN() {
            return parkedAge == UNKNOWN ? Double.NaN : (double) parkedAge;
        }
    }
}
