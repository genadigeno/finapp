package com.finapp.app.kyc;

import com.finapp.kyc.CounterpartyScreeningObserver;
import com.finapp.kyc.CounterpartyScreeningStatus;
import com.finapp.kyc.CounterpartyScreeningStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The counterparty screening's meters (`P9-TSK-016`, ADR-0081): {@code finapp.kyc.counterparty.screening{outcome}}
 * per committed decision, and the database-read gauges {@code finapp.kyc.counterparty.review.pending} and
 * {@code .review.age} (seconds since the oldest screening in review was routed to a person - alerting: a
 * hit nobody judges leaves a payee unpayable). Counts and ages only - never a name or a reference.
 */
@Slf4j
public final class CounterpartyScreeningMetrics implements CounterpartyScreeningObserver {

    public static final String SCREENING = "finapp.kyc.counterparty.screening";
    public static final String REVIEW_PENDING = "finapp.kyc.counterparty.review.pending";
    public static final String REVIEW_AGE = "finapp.kyc.counterparty.review.age";
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    private final Supplier<CounterpartyScreeningStore.ReviewBacklog> backlog;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public CounterpartyScreeningMetrics(
            Supplier<CounterpartyScreeningStore.ReviewBacklog> backlog, Clock clock, MeterRegistry registry) {
        this.backlog = Objects.requireNonNull(backlog, "backlog must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        for (CounterpartyScreeningStatus status : CounterpartyScreeningStatus.values()) {
            if (status != CounterpartyScreeningStatus.REQUESTED) {
                counter(status);
            }
        }
        Gauge.builder(REVIEW_PENDING, this, self -> self.reading().backlog().map(b -> (double) b.pending()).orElse(Double.NaN))
                .description("Counterparty screenings waiting for a person (INV-KYC-04). NaN when unreadable."
                        + " Fleet-wide: max(), never sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(REVIEW_AGE, this, self -> self.reading().backlog()
                        .map(b -> b.oldest().map(self::secondsSince).orElse(0d))
                        .orElse(Double.NaN))
                .description("Seconds since the oldest counterparty screening in review was routed to a person"
                        + " - 0 when none, NaN when unreadable. Alerting")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    @Override
    public void decided(CounterpartyScreeningStatus status) {
        counter(status).increment();
    }

    private Counter counter(CounterpartyScreeningStatus status) {
        return Counter.builder(SCREENING)
                .tag("outcome", status.name().toLowerCase(Locale.ROOT))
                .description("Counterparty screening decisions: clear, in_review, unavailable, released, blocked."
                        + " A count, never a name")
                .register(registry);
    }

    private double secondsSince(Instant then) {
        return Math.max(0, Duration.between(then, clock.instant()).toMillis()) / 1000d;
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.takenAt().plus(MIN_REFRESH).isBefore(clock.instant())) {
            return current;
        }
        Reading fresh;
        try {
            fresh = new Reading(clock.instant(), Optional.of(backlog.get()));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the counterparty review backlog; its gauges report absent rather than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Reading(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private record Reading(Instant takenAt, Optional<CounterpartyScreeningStore.ReviewBacklog> backlog) {}
}
