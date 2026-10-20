package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplicationStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.bnpl.application.awaiting.age} (`P12-TSK-026`; the Phase 11 completion gate's A5-03 applied forward, as the
 * task's scope asks; lending's {@code LoanAwaitingDecisionMetrics}): seconds the oldest BNPL application
 * {@code AWAITING_DECISION} has waited since its submission, on the database clock - 0 when none waits, NaN when unreadable,
 * never zero for a read that failed. An application waits on credit's decision request, which credit closes at its
 * validity at the latest, and the origination step then closes it undecided; one older than that validity and an hour
 * means the origination sweep is not acting on it ({@code BnplObjectives#awaitingDecision}). Read from the database, so
 * every instance reports the same value - aggregate with {@code max()}, never {@code sum()}; cached 15 s behind a
 * per-instance refresh floor - never truth. Never an application id, a session or a party (ADR-0072).
 */
@Slf4j
public final class BnplAwaitingDecisionMetrics {

    /** Exported as {@code finapp_bnpl_application_awaiting_age_seconds}. */
    public static final String AWAITING_AGE = "finapp.bnpl.application.awaiting.age";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final BnplApplicationStore store;
    private final BnplAccrualMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> age) {}

    public BnplAwaitingDecisionMetrics(BnplApplicationStore store, BnplAccrualMetrics.Connections connections, Clock clock,
            MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Gauge.builder(AWAITING_AGE, this, BnplAwaitingDecisionMetrics::oldestAwaitingSeconds)
                .description("Seconds the oldest BNPL application AWAITING_DECISION has waited since its submission, on"
                        + " the database clock - 0 when none waits, NaN when unreadable. Alert above the longest credit"
                        + " request validity and an hour. Fleet-wide from every instance: aggregate with max(), never"
                        + " sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(Objects.requireNonNull(registry, "registry"));
    }

    private double oldestAwaitingSeconds() {
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
            fresh = new Cached(clock.instant(), Optional.of(store.oldestAwaitingDecisionAge(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest BNPL application awaiting a decision could not be read: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
