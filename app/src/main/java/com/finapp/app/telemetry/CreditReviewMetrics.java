package com.finapp.app.telemetry;

import com.finapp.credit.UnderwritingCaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.credit.review.age} (`P10-TSK-018`, PHASE_10_PLAN.md section 15, ADR-0089 point 7): how long, in seconds,
 * the oldest {@code OPEN} underwriting case has waited - 0 when none waits, NaN when unreadable, never zero for a read
 * that failed. A referral queue nobody works is a silent decline; this gauge makes it loud long before the request's
 * validity runs out (alerted above the review objective by `P10-TSK-020`). Read from the database on its clock, so every
 * instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s, the
 * {@link RuleSetMissingMetrics} floor. No tag: an age names no case, party or person.
 */
@Slf4j
public final class CreditReviewMetrics {

    /** Spelled with dots - exported as {@code finapp_credit_review_age}, the plan's name. */
    public static final String AGE = "finapp.credit.review.age";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final UnderwritingCaseStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, OptionalDouble.empty()));

    private record Cached(Instant takenAt, OptionalDouble seconds) {}

    public CreditReviewMetrics(UnderwritingCaseStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        Gauge.builder(AGE, this, CreditReviewMetrics::oldestOpenSeconds)
                .description("Seconds the oldest OPEN credit review case has waited - 0 when none waits, NaN when"
                        + " unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    private double oldestOpenSeconds() {
        return refreshed().seconds().orElse(Double.NaN);
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            Optional<Duration> age = store.oldestOpenAge(connection);
            fresh = new Cached(clock.instant(), OptionalDouble.of(age.map(Duration::toMillis).orElse(0L) / 1000.0));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest open credit review case could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), OptionalDouble.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
