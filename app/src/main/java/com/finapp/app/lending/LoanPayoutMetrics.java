package com.finapp.app.lending;

import com.finapp.lending.LoanPayoutSteps;
import com.finapp.lending.LoanPayoutStore;
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
 * The payout's series (`P11-TSK-015`, PHASE_11_PLAN.md section 15):
 *
 * <ul>
 *   <li>{@code finapp.lending.payout{outcome}} - every committed payout step that did something, counted once after its
 *       transaction committed: dispatched, retried (payments gave no answer now), or concluded - paid out, failed, not
 *       dispatched. An observation of a still-undecided withdrawal counts nothing (the age gauge shows it). Registered
 *       eagerly for every outcome.
 *   <li>{@code finapp.lending.payout.dispatched.age} - seconds the oldest payout not yet concluded ({@code PENDING},
 *       {@code DISPATCHING} or {@code DISPATCHED}) has held the borrower's funds, from its birth, on the database clock; 0
 *       when none is open, NaN when unreadable - never zero for a read that failed. The alert sits above payments' outcome
 *       objective (`P11-TSK-030`): a payout payments holds {@code UNKNOWN} is the platform's ambiguity, one stuck before
 *       payments took it (no push rail composed, a defect re-driving) the platform's too, and the borrower's interest
 *       waits on both. Read from the database, so every instance reports the same
 *       value - aggregate with {@code max()}, never {@code sum()}; cached 15 s behind a per-instance refresh floor.
 * </ul>
 *
 * <p>Never an amount, a party, a loan or a withdrawal in a tag (ADR-0072).
 */
@Slf4j
public final class LoanPayoutMetrics {

    /** Exported as {@code finapp_lending_payout}. */
    public static final String PAYOUT = "finapp.lending.payout";

    /** Exported as {@code finapp_lending_payout_dispatched_age}. */
    public static final String DISPATCHED_AGE = "finapp.lending.payout.dispatched.age";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The closed set of outcomes. */
    public enum Outcome {
        DISPATCHED,
        RETRYING,
        PAID_OUT,
        FAILED,
        NOT_DISPATCHED;

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
    private final LoanPayoutStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> age) {}

    public LoanPayoutMetrics(LoanPayoutStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
        Gauge.builder(DISPATCHED_AGE, this, LoanPayoutMetrics::oldestUnconcludedSeconds)
                .description("Seconds the oldest unconcluded loan payout has held the borrower's funds, from its birth, on the"
                        + " database clock - 0 when none is open, NaN when unreadable. Alert above payments' outcome"
                        + " objective."
                        + " Fleet-wide from every instance: aggregate with max(), never sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    /** One committed payout step's effect - counted only when it did something. */
    public void record(LoanPayoutSteps.Outcome step) {
        Objects.requireNonNull(step, "step");
        Outcome outcome = switch (step.kind()) {
            case DISPATCHED -> Outcome.DISPATCHED;
            case RETRYING -> Outcome.RETRYING;
            case PAID_OUT -> Outcome.PAID_OUT;
            case FAILED -> Outcome.FAILED;
            case NOT_DISPATCHED -> Outcome.NOT_DISPATCHED;
            case NOTHING, AWAITING -> null;
        };
        if (outcome != null) {
            counter(outcome).increment();
        }
    }

    private double oldestUnconcludedSeconds() {
        return refreshed().age()
                .map(age -> age.map(waited -> waited.toMillis() / 1000.0).orElse(0.0))
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
            fresh = new Cached(clock.instant(), Optional.of(store.oldestUnconcludedAge(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest unconcluded payout could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(PAYOUT)
                .description("Loan payout steps that did something - dispatched through payments, retried for want of an"
                        + " answer, or concluded paid out, failed or not dispatched - by outcome")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
