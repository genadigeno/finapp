package com.finapp.app.fx;

import com.finapp.fx.RateSnapshotStore;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceRateFetch;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.fx.RateSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The reference rate's series (`P9-TSK-005`, ADR-0075's operational impact, ADR-0072): counts and
 * ages only - never a rate.
 *
 * <ul>
 *   <li>{@value #AGE}{@code {pair}} - seconds since the pair's latest snapshot was received,
 *       computed ON THE DATABASE CLOCK ({@code statement_timestamp() - received_at}), one series
 *       per declared pair registered eagerly, NaN when unreadable or never fetched, never zero.
 *       The alert fires past the maximum age; a stale reference already fails closed, and this
 *       makes it loud long before customers see a {@code 503}.
 *   <li>{@value #FETCH}{@code {outcome}} - one count per round outcome and per row: stored,
 *       not_newer, rejected, paced, the four fetch failures, crashed.
 * </ul>
 *
 * <p>The age is read at most every {@link #MIN_REFRESH} per instance, and between reads it climbs
 * by the time elapsed since the reading; that cache is a scrape-cost bound for a GAUGE, and no
 * decision ever reads it ({@code RateSnapshotStore.freshLatest} is the only freshness that
 * decides anything).
 */
@Slf4j
public final class FxRateMetrics {

    public static final String AGE = "finapp.fx.rate.age";
    public static final String FETCH = "finapp.fx.rate.fetch";

    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** Opens a connection for one gauge reading. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final RateSnapshotStore<Connection> snapshots;
    private final Connections connections;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Reading> cached = new AtomicReference<>(Reading.none());

    public FxRateMetrics(
            RateSnapshotStore<Connection> snapshots,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.snapshots = snapshots;
        this.connections = connections;
        this.clock = clock;
        this.registry = registry;
        for (ReferencePair pair : ReferenceSourceDeclaration.PAIRS) {
            String code = pair.code();
            Gauge.builder(AGE, this, self -> self.ageOf(code))
                    .tag("pair", code)
                    .description(
                            "Seconds since this pair's latest reference snapshot was received, on"
                                    + " the database clock. NaN when unreadable or never fetched,"
                                    + " never zero. A reference older than the policy's maximum"
                                    + " age fails closed; alert past it. Fleet-wide: max(), never"
                                    + " sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (String outcome :
                new String[] {"stored", "not_newer", "rejected", "paced", "crashed"}) {
            counter(outcome);
        }
        for (RateSource.FetchFailure failure : RateSource.FetchFailure.values()) {
            counter(failure.name().toLowerCase(Locale.ROOT));
        }
    }

    /** One round's counts, after it ran. */
    public void recorded(ReferenceRateFetch.Result result) {
        if (result.paced()) {
            counter("paced").increment();
            return;
        }
        result.failure()
                .ifPresent(failure -> counter(failure.name().toLowerCase(Locale.ROOT)).increment());
        counter("stored").increment(result.stored());
        counter("not_newer").increment(result.notNewer());
        counter("rejected").increment(result.rejected());
    }

    /** A round that threw past its own containment. */
    public void crashed() {
        counter("crashed").increment();
    }

    private Counter counter(String outcome) {
        return Counter.builder(FETCH)
                .tag("outcome", outcome)
                .description(
                        "Reference rate fetch rounds and rows, by outcome: stored as the pair's"
                                + " newest, not newer (a duplicate or replay - stored nothing),"
                                + " rejected (refused by the adapter, the declaration or the"
                                + " database), paced (another instance held the window), a"
                                + " failed fetch, or a crashed round. A count, never a rate")
                .register(registry);
    }

    private double ageOf(String pairCode) {
        Reading reading = reading();
        if (reading.ages().isEmpty()) {
            return Double.NaN;
        }
        Duration age = reading.ages().get().get(pairCode);
        if (age == null) {
            return Double.NaN;
        }
        Duration sinceReading = Duration.between(reading.takenAt(), clock.instant());
        // Whole milliseconds to the registry boundary; the ToDoubleFunction is Micrometer's.
        return age.plus(sinceReading.isNegative() ? Duration.ZERO : sinceReading).toMillis()
                / 1000.0;
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Reading fresh;
        try (Connection connection = connections.open()) {
            Map<String, Duration> ages = new HashMap<>();
            for (ReferencePair pair : ReferenceSourceDeclaration.PAIRS) {
                snapshots
                        .age(connection, ReferenceSourceDeclaration.SOURCE, pair)
                        .ifPresent(age -> ages.put(pair.code(), age));
            }
            fresh = new Reading(clock.instant(), Optional.of(ages));
        } catch (Exception unreadable) {
            log.warn(
                    "Could not read the reference snapshots' ages; the age gauges report absent"
                            + " rather than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Reading(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private record Reading(Instant takenAt, Optional<Map<String, Duration>> ages) {

        static Reading none() {
            return new Reading(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
