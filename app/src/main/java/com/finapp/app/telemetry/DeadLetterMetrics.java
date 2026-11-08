package com.finapp.app.telemetry;

import com.finapp.platform.deadletter.DeadLetterStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.deadletter.parked}, a gauge by consumer group (`P15-TSK-028`; {@code PHASE_15_PLAN.md} section 11.2).
 *
 * <p><strong>Eager, one series per group the handlers name and one for the rest.</strong> Registered at start, so the alert evaluates a
 * series from the first scrape, zero where nothing is parked. {@value #OTHER} counts a group no handler of this release names: a
 * record parked before its handler was removed would otherwise be invisible, and a parked record nobody can see is the failure this
 * exists to prevent.
 *
 * <p>One reading behind every series, so they describe one instant ({@code OutboxMetrics}' reasoning), and {@link Double#NaN} when the
 * database cannot be read: absent, never a comforting zero. Every instance reports the same system-wide figure over the shared
 * database, so aggregate with {@code max()}, never {@code sum()}. Decides nothing, so it needs no {@code DISTRIBUTED_EXECUTION.md} row.
 */
@Slf4j
final class DeadLetterMetrics {

    static final String PARKED = "finapp.deadletter.parked";

    /** The tag value for a group no handler of this release names. */
    static final String OTHER = "other";

    /** A scrape asks every series at once. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    private final DeadLetterStore store;
    private final Connections connections;
    private final Clock clock;
    private final Set<String> groups;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(null, Map.of(), false));

    DeadLetterMetrics(Collection<String> groups, DeadLetterStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        this.groups = new TreeSet<>(groups);
        for (String group : this.groups) {
            register(registry, group, () -> reading().of(group));
        }
        register(registry, OTHER, () -> reading().besides(this.groups));
    }

    @FunctionalInterface
    private interface Series {
        double value();
    }

    private void register(MeterRegistry registry, String group, Series series) {
        Gauge.builder(PARKED, series, Series::value)
                .description("Inbox records parked after their bounded retries, awaiting a decision, by consumer group")
                .tag("group", group)
                .strongReference(true)
                .register(registry);
    }

    private Cached reading() {
        Instant now = clock.instant();
        Cached current = cached.get();
        if (!current.isStale(now)) {
            return current;
        }
        Cached refreshed;
        try (Connection connection = connections.open()) {
            refreshed = new Cached(now, store.parkedByGroup(connection), true);
        } catch (SQLException | RuntimeException unreadable) {
            // The class only: a JDBC message can name hosts and values. Once per refresh, so the floor is the log's rate limit.
            log.warn("Could not read the parked records for metrics: {}", unreadable.getClass().getSimpleName());
            refreshed = new Cached(now, Map.of(), false);
        }
        cached.compareAndSet(current, refreshed);
        return refreshed;
    }

    private record Cached(Instant takenAt, Map<String, Long> counts, boolean known) {

        boolean isStale(Instant now) {
            return takenAt == null || !now.isBefore(takenAt.plus(MIN_REFRESH));
        }

        double of(String group) {
            return known ? counts.getOrDefault(group, 0L) : Double.NaN;
        }

        double besides(Set<String> named) {
            if (!known) {
                return Double.NaN;
            }
            return counts.entrySet().stream().filter(entry -> !named.contains(entry.getKey())).mapToLong(Map.Entry::getValue).sum();
        }
    }
}
