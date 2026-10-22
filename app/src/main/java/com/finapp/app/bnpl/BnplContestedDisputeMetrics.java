package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplDisputeStore;
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
 * {@code finapp.bnpl.dispute.contested.age} (the Phase 12 to 13 transition gate's A7-2; {@link BnplAwaitingDecisionMetrics}'
 * shape): seconds the oldest {@code MERCHANT_RESPONDED} BNPL dispute has awaited an operator since its merchant contested
 * it, on the database clock - 0 when none waits, NaN when unreadable, never zero for a read that failed. A contested dispute
 * has no deadline: until an operator resolves it its held instalments are never collected, aged or charged, every refund of
 * its purchase is refused and its merchant cannot close - so its age is watched ({@code BnplDisputeContestedTooLong} above
 * {@code BnplObjectives#CONTESTED_DISPUTE}) and the operator works the queue ({@code GET /v1/operator/bnpl-disputes}).
 * Registered eagerly; read from the database, so every instance reports the same value - aggregate with {@code max()},
 * never {@code sum()}; cached 15 s behind a per-instance refresh floor - never truth. Never a dispute, a purchase, a party or
 * an amount (ADR-0072).
 */
@Slf4j
public final class BnplContestedDisputeMetrics {

    /** Exported as {@code finapp_bnpl_dispute_contested_age_seconds}. */
    public static final String CONTESTED_AGE = "finapp.bnpl.dispute.contested.age";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final BnplDisputeStore store;
    private final BnplAccrualMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> age) {}

    public BnplContestedDisputeMetrics(BnplDisputeStore store, BnplAccrualMetrics.Connections connections, Clock clock,
            MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Gauge.builder(CONTESTED_AGE, this, BnplContestedDisputeMetrics::oldestContestedSeconds)
                .description("Seconds the oldest contested (MERCHANT_RESPONDED) BNPL dispute has awaited an operator since"
                        + " its merchant answered, on the database clock - 0 when none waits, NaN when unreadable. Alert"
                        + " above the contested-dispute objective. Fleet-wide from every instance: aggregate with max(),"
                        + " never sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(Objects.requireNonNull(registry, "registry"));
    }

    private double oldestContestedSeconds() {
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
            fresh = new Cached(clock.instant(), Optional.of(store.oldestContestedAge(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest contested BNPL dispute could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
