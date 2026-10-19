package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAccrualStore;
import com.finapp.bnpl.BnplAccruals;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Pay-in-N accrual's series (`P12-TSK-017`; {@code PHASE_12_PLAN.md} section 15, which this task extends with the lag;
 * lending's {@code LoanServicingMetrics} accrual half):
 *
 * <ul>
 *   <li>{@code finapp.bnpl.accrual{outcome}} - each accrued date of a committed T-acc call, counted once after its
 *       transaction committed: {@code posted} (the row and its F2a entry) or {@code zero} (the row alone - a zero day); and
 *       each T-acc call that failed ({@code failed} - rolled back, retried at the claim's cadence). Registered eagerly for
 *       every outcome;
 *   <li>{@code finapp.bnpl.accrual.lag} - seconds since the end of the oldest elapsed date of an {@code ACTIVE} pay-in-N
 *       obligation's accrual span that has not accrued, on the database clock in its pinned zone
 *       ({@link BnplAccrualStore#oldestLag}); 0 when every such date is accrued, NaN when unreadable - never zero for a read
 *       that failed. The alert sits above one day ({@link #LAG_ALERT}, the lending precedent; its rule is `P12-TSK-026`'s
 *       {@code bnpl.yml}). Read from the database, so every instance reports the same value - aggregate with
 *       {@code max()}, never {@code sum()}; cached 15 s behind a per-instance refresh floor - never truth.
 * </ul>
 *
 * <p>Never an amount, a party, an obligation id or a date in a tag (ADR-0072).
 */
@Slf4j
public final class BnplAccrualMetrics {

    /** Exported as {@code finapp_bnpl_accrual_total}, the plan's name. */
    public static final String ACCRUAL = "finapp.bnpl.accrual";

    /** Exported as {@code finapp_bnpl_accrual_lag_seconds}. */
    public static final String LAG = "finapp.bnpl.accrual.lag";

    /** The lag above which an elapsed date is late - one day, lending's {@code LendingObjectives.ACCRUAL_LAG}. */
    public static final Duration LAG_ALERT = Duration.ofDays(1);

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The closed set of outcomes. */
    public enum Outcome {
        POSTED,
        ZERO,
        FAILED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A connection source - its own seam, the metrics precedent (no {@code DataSource} named in the unit tier). */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final BnplAccrualStore store;
    private final Connections connections;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> lag) {}

    public BnplAccrualMetrics(BnplAccrualStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
        Gauge.builder(LAG, this, BnplAccrualMetrics::lagSeconds)
                .description("Seconds since the end of the oldest elapsed date of an ACTIVE pay-in-N obligation's accrual"
                        + " span that has not accrued, on the database clock in its pinned zone; 0 when none, NaN when"
                        + " unreadable. Alert above one day. Fleet-wide from every instance: aggregate with max(), never"
                        + " sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    /** One committed T-acc call - each accrued date counted once, never a figure. */
    public void record(BnplAccruals.Outcome call) {
        Objects.requireNonNull(call, "call");
        // One increment per item - a count, never a floating-point amount (INV-MON-01's rule over every call).
        Counter posted = counter(Outcome.POSTED);
        for (int date = 0; date < call.posted(); date++) {
            posted.increment();
        }
        Counter zero = counter(Outcome.ZERO);
        for (int date = 0; date < call.zero(); date++) {
            zero.increment();
        }
    }

    /** One T-acc call that failed and rolled back. */
    public void failed() {
        counter(Outcome.FAILED).increment();
    }

    private double lagSeconds() {
        return refreshed().lag()
                .map(lag -> lag.map(elapsed -> elapsed.toMillis() / 1000.0).orElse(0.0))
                .orElse(Double.NaN);
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), Optional.of(store.oldestLag(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The BNPL accrual lag could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(ACCRUAL)
                .description("Pay-in-N dates accrued by committed T-acc calls - posted (the row and its entry) or zero (the"
                        + " row alone) - and the T-acc calls that failed")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
