package com.finapp.app.telemetry;

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
 * {@code finapp.payments.dispute.deadline.near} (`P7-TSK-014`, ADR-0061 §7, `PHASE_7_PLAN.md` §15):
 * how many chargebacks still await an answer the PSP took, with the network's respond-by
 * deadline inside the alarm window — near, or already missed (a missed deadline keeps the alarm
 * on until the network resolves the dispute).
 *
 * <p><strong>The platform's clock raises the alarm and decides nothing</strong> (ADR-0061 §7):
 * this series is the only place the deadline meets a clock besides the refusal of the platform's
 * own late dispatch. The {@code NegativePositionMetrics} stance verbatim: eager, NaN when
 * unreadable and never a false zero, a refresh floor as the rate limit, a count and never an
 * identifier ({@code INV-AUD-02}), fleet-wide — aggregate with {@code max()}, never {@code sum()}.
 * Per instance and non-authoritative: it decides nothing, so it needs no
 * {@code DISTRIBUTED_EXECUTION.md} §3 row.
 */
@Slf4j
final class DisputeDeadlineMetrics {

    /** {@code finapp.payments.dispute.deadline.near} — chargebacks near or past their deadline. */
    static final String DEADLINE_NEAR = "finapp.payments.dispute.deadline.near";

    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code PaymentMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The dispute read seam ({@code DisputeStore.countDeadlinesNear}). */
    @FunctionalInterface
    interface Counts {
        long read(Connection connection, Instant horizon);
    }

    private final Counts counts;
    private final Connections connections;
    private final Clock clock;
    private final Duration window;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    DisputeDeadlineMetrics(
            Counts counts,
            Connections connections,
            Clock clock,
            Duration window,
            MeterRegistry registry) {
        this.counts = Objects.requireNonNull(counts, "counts must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("the deadline alarm window must be positive");
        }
        Gauge.builder(DEADLINE_NEAR, this, self -> self.reading().valueOrNaN())
                .description(
                        "Chargebacks awaiting an answer the PSP took whose respond-by deadline"
                                + " falls inside the alarm window or has passed (ADR-0061"
                                + " section 7) - the alarm, never a decision. NaN when"
                                + " unreadable, never zero. Fleet-wide: aggregate with max(),"
                                + " never sum()")
                .strongReference(true)
                .register(registry);
    }

    private Cached reading() {
        Cached current = cached.get();
        Instant now = clock.instant();
        if (!current.isStaleAt(now)) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            fresh = new Cached(now, counts.read(connection, now.plus(window)), false);
        } catch (SQLException | RuntimeException unreadable) {
            log.warn(
                    "Could not count the disputes near their deadline; the gauge reports absent"
                            + " rather than a false zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(now, 0, true);
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken — per instance, non-authoritative. */
    private record Cached(Instant takenAt, long count, boolean unknown) {

        static Cached empty() {
            return new Cached(Instant.MIN, 0, true);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double valueOrNaN() {
            return unknown ? Double.NaN : (double) count;
        }
    }
}
