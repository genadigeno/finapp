package com.finapp.app.telemetry;

import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * The settlement file gauges (`P8-TSK-003`, `PHASE_8_PLAN.md` §15):
 * {@code finapp.settlement.file.pending} and {@code finapp.settlement.file.age}, per
 * {@code source}.
 *
 * <h2>What they are for</h2>
 *
 * <p>{@code INV-SET-07}'s operational face. An unattested upload is inert <em>by design</em> —
 * and invisible by default: nothing else says "a file is waiting for its second person". A
 * pending count that stays above zero with an age that climbs is evidence either that no
 * attester is looking or that a parse is stuck (`P8-TSK-008`'s backoff lands in the same
 * non-terminal set), and the age of the oldest is the alertable number (ADR-0066 §2: the file
 * waits for its attester, <em>visibly</em>).
 *
 * <h2>Per source, eager, from the compiled register</h2>
 *
 * <p>The {@code source} tag is bounded by the compiled register (`P8-TSK-002`'s
 * {@code MetricNames} argument), and every declared source's two series exist from the first
 * scrape at an honest value — a series that appears on its first pending file reads as "no
 * data" exactly when "zero waiting" is the signal. A source with no waiting file reads
 * <strong>zero</strong>, which is true; only an unreadable database reads {@link Double#NaN}
 * (`P1-TSK-029`: absent is alertable, a comforting zero is not).
 *
 * <h2>Fleet-wide, and floored</h2>
 *
 * <p>Every instance computes the same answer over the shared database — one indexed
 * {@code GROUP BY} per floor period per instance — so a dashboard aggregates with
 * {@code max()}, <strong>never {@code sum()}</strong> (the {@code LedgerMetrics} rule).
 * Nothing here is authoritative and nothing is written: a stale reading is a stale reading.
 */
@Slf4j
public final class SettlementFileMetrics {

    /** {@code finapp.settlement.file.pending} — non-terminal files, per source. */
    public static final String PENDING = "finapp.settlement.file.pending";

    /** {@code finapp.settlement.file.age} — seconds the oldest has waited, per source. */
    public static final String AGE = "finapp.settlement.file.age";

    /** The cheap-read floor, the sibling gauges' own: one indexed aggregate, not a fold. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code LedgerMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final SettlementFileStore<Connection> store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    public SettlementFileMetrics(
            SettlementFileStore<Connection> store,
            SettlementSources sources,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;

        // Eager per declared source (the P7-TSK-015 rule, SettlementMeters' own): the
        // attestation alert has something to evaluate from the first scrape.
        for (SettlementSourceDescriptor source : sources.declared()) {
            String code = source.code();
            Gauge.builder(PENDING, this, self -> self.pendingOf(code))
                    .tag("source", code)
                    .description(
                            "Settlement files in a non-terminal state for this source - an"
                                    + " unattested upload among them (INV-SET-07: inert by"
                                    + " design, visible by this series). A count, never an"
                                    + " amount. NaN when unreadable, never zero. Fleet-wide"
                                    + " from every instance: aggregate with max(), never"
                                    + " sum()")
                    // No baseUnit (the P0-TSK-029 finding: Micrometer appends it to the name).
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(AGE, this, self -> self.ageOf(code))
                    .tag("source", code)
                    .description(
                            "Seconds the OLDEST non-terminal settlement file of this source"
                                    + " has waited since reception - the attestation and"
                                    + " stuck-parse alert's series: a climbing age is a file"
                                    + " nobody is attesting or a parse that is not landing."
                                    + " Zero when nothing waits, NaN when unreadable."
                                    + " Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double pendingOf(String sourceCode) {
        return reading()
                .bySource()
                .map(pending -> (double) countOf(pending, sourceCode))
                .orElse(Double.NaN);
    }

    private double ageOf(String sourceCode) {
        return reading()
                .bySource()
                .map(pending -> ageSecondsOf(pending, sourceCode))
                .orElse(Double.NaN);
    }

    private static long countOf(
            Map<String, SettlementFileStore.PendingReading> pending, String sourceCode) {
        SettlementFileStore.PendingReading reading = pending.get(sourceCode);
        return reading == null ? 0 : reading.pending();
    }

    private double ageSecondsOf(
            Map<String, SettlementFileStore.PendingReading> pending, String sourceCode) {
        SettlementFileStore.PendingReading reading = pending.get(sourceCode);
        if (reading == null || reading.oldestReceivedAt().isEmpty()) {
            return 0;
        }
        // Recomputed from the cached oldest at every scrape, so the age climbs between
        // refreshes instead of stair-stepping - the sweeper's own measure of "how long".
        long seconds =
                Duration.between(reading.oldestReceivedAt().get(), clock.instant()).toSeconds();
        return Math.max(seconds, 0);
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            fresh =
                    new Cached(
                            clock.instant(),
                            Optional.of(
                                    store.pendingBySource(connection).stream()
                                            .collect(
                                                    Collectors.toMap(
                                                            SettlementFileStore.PendingReading
                                                                    ::sourceCode,
                                                            reading -> reading))));
        } catch (Exception unreadable) {
            // Never the exception's message at INFO and never an identifier: this runs on
            // every scrape past the floor (INV-AUD-02, and plain operability).
            log.warn(
                    "Could not read the pending settlement files; the gauges report absent"
                            + " rather than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    /**
     * One reading and when it was taken. Non-authoritative and per instance, so it appears in
     * no register in {@code DISTRIBUTED_EXECUTION.md} §3: it decides nothing, and two
     * instances refreshing at once is two harmless queries, not a race.
     */
    private record Cached(
            Instant takenAt,
            Optional<Map<String, SettlementFileStore.PendingReading>> bySource) {

        static Cached empty() {
            return new Cached(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
