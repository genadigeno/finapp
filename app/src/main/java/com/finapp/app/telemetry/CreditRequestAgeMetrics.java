package com.finapp.app.telemetry;

import com.finapp.credit.DecisionRequestStatus;
import com.finapp.credit.DecisionRequestStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.credit.request.open.age{status}} (`P10-TSK-020`, PHASE_10_PLAN.md section 15): per OPEN state, how long,
 * in seconds since its submission, the oldest request still in that state has been open - 0 when none is, NaN when
 * unreadable, never zero for a read that failed. A request open past its validity outside {@code in_review} means the
 * expiry is not being taken (alerted above {@link CreditObjectives#requestValidity()}); {@code in_review} is tagged apart
 * because a case an underwriter holds is never expired under them - the review age is that queue's alert. Read from the
 * database on its clock, so every instance reports the same value - aggregate with {@code max()}, never {@code sum()}.
 * Cached for 15 s, the {@link RuleSetMissingMetrics} floor. A state's name is the only tag: an age names no request,
 * party or amount.
 */
@Slf4j
public final class CreditRequestAgeMetrics {

    /** Spelled with dots - exported as {@code finapp_credit_request_open_age_seconds}. */
    public static final String AGE = "finapp.credit.request.open.age";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final DecisionRequestStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<DecisionRequestStatus, Duration>> ages) {}

    public CreditRequestAgeMetrics(
            DecisionRequestStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        for (DecisionRequestStatus status : DecisionRequestStatus.values()) {
            if (!status.open()) {
                continue;
            }
            Gauge.builder(AGE, this, self -> self.oldestSeconds(status))
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .description("Seconds since submission of the oldest credit decision request still in this open"
                            + " state - 0 when none is, NaN when unreadable. Fleet-wide from every instance: aggregate"
                            + " with max(), never sum()")
                    .baseUnit("seconds")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double oldestSeconds(DecisionRequestStatus status) {
        return refreshed().ages()
                .map(ages -> Optional.ofNullable(ages.get(status)).map(Duration::toMillis).orElse(0L) / 1000.0)
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
            fresh = new Cached(clock.instant(), Optional.of(Map.copyOf(store.oldestOpenAges(connection))));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest open credit decision requests could not be read: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
