package com.finapp.app.lending;

import com.finapp.lending.LoanServicing;
import com.finapp.lending.LoanServicingStore;
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
 * The accrual's series (`P11-TSK-016`, PHASE_11_PLAN.md section 15):
 *
 * <ul>
 *   <li>{@code finapp.lending.accrual{outcome}} - each accrued date of a committed servicing step, counted once after its
 *       transaction committed: {@code posted} (the row and its entry) or {@code zero} (the row alone - a zero day); and
 *       each servicing step that failed ({@code failed} - rolled back, retried at the claim's cadence). Registered eagerly
 *       for every outcome.
 *   <li>{@code finapp.lending.accrual.lag} - seconds since the end of the oldest elapsed date an instalment loan in
 *       servicing has not accrued, on the database clock in its pinned zone; 0 when every elapsed date is accrued, NaN
 *       when unreadable - never zero for a read that failed. The alert sits above one day (`P11-TSK-030`). Read from the
 *       database, so every instance reports the same value - aggregate with {@code max()}, never {@code sum()}; cached
 *       15 s behind a per-instance refresh floor - never truth.
 * </ul>
 *
 * <p>Never an amount, a party, a loan id or a date in a tag (ADR-0072).
 */
@Slf4j
public final class LoanServicingMetrics {

    /** Exported as {@code finapp_lending_accrual}, the plan's name. */
    public static final String ACCRUAL = "finapp.lending.accrual";

    /** Exported as {@code finapp_lending_accrual_lag}. */
    public static final String LAG = "finapp.lending.accrual.lag";

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

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final MeterRegistry registry;
    private final LoanServicingStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> lag) {}

    public LoanServicingMetrics(LoanServicingStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
        Gauge.builder(LAG, this, LoanServicingMetrics::lagSeconds)
                .description("Seconds since the end of the oldest elapsed date an instalment loan in servicing has not"
                        + " accrued, on the database clock in its pinned zone - 0 when none, NaN when unreadable. Alert"
                        + " above one day. Fleet-wide from every instance: aggregate with max(), never sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    /** One committed servicing step - its accrued dates counted. */
    public void record(LoanServicing.Outcome step) {
        Objects.requireNonNull(step, "step");
        if (step.posted() > 0) {
            counter(Outcome.POSTED).increment(step.posted());
        }
        if (step.zero() > 0) {
            counter(Outcome.ZERO).increment(step.zero());
        }
    }

    /** One servicing step that failed and rolled back. */
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
            fresh = new Cached(clock.instant(), Optional.of(store.oldestUnaccruedLag(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The accrual lag could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(ACCRUAL)
                .description("Accrued dates of committed servicing steps - posted (the row and its entry) or zero (the row"
                        + " alone) - and servicing steps that failed and rolled back")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
