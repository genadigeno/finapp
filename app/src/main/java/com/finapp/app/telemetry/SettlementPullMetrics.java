package com.finapp.app.telemetry;

import com.finapp.settlement.PullOutcomeObserver;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementReportCollector;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The pull's two series (`P8-TSK-021`, `PHASE_8_PLAN.md` §15; ADR-0072: counts and ages, never
 * an amount):
 *
 * <ul>
 *   <li>{@code finapp.settlement.source.silence} — gauge per declared source: seconds since the
 *       source's last ACCEPTED batch, whatever channel brought it. It reads acceptances, not
 *       pulls, so a silent source is visible whether its evidence is pulled or uploaded. NaN when
 *       unreadable — and when the source has never had a batch accepted, since "since when" has
 *       no answer yet — never zero: a zero would read as "just heard from".
 *   <li>{@code finapp.settlement.pull.failure} — counter per source and {@code outcome}: a
 *       fetch that produced no delivery ({@code not_yet} and the collector's closed failure
 *       vocabulary). What a pull DID deliver is the door's own {@code file.received} and
 *       {@code delivery.refused}.
 * </ul>
 *
 * <p>Both eager per declared source (the P7-TSK-015 rule), so the silence alert and the failure
 * rate have series from the first scrape.
 */
@Slf4j
public final class SettlementPullMetrics implements PullOutcomeObserver {

    /** {@code finapp.settlement.source.silence} — seconds since the last accepted batch. */
    public static final String SILENCE = "finapp.settlement.source.silence";

    /** {@code finapp.settlement.pull.failure} — fetches that produced no delivery. */
    public static final String PULL_FAILURE = "finapp.settlement.pull.failure";

    /** The cheap-read floor, the sibling gauges' own. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code LedgerMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final SettlementFileStore<Connection> files;
    private final SettlementBatchStore<Connection> batches;
    private final Connections connections;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    public SettlementPullMetrics(
            SettlementFileStore<Connection> files,
            SettlementBatchStore<Connection> batches,
            SettlementSources sources,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.files = files;
        this.batches = batches;
        this.connections = connections;
        this.clock = clock;
        this.registry = registry;
        for (SettlementSourceDescriptor source : sources.declared()) {
            String code = source.code();
            Gauge.builder(SILENCE, this, self -> self.silenceOf(code))
                    .tag("source", code)
                    .description(
                            "Seconds since this source's last ACCEPTED settlement batch, by any"
                                    + " channel - the silent-source alert's series: a file that"
                                    + " never came is visible here before its expectations age"
                                    + " into breaks. NaN when unreadable or never accepted,"
                                    + " never zero. Fleet-wide: aggregate with max(), never"
                                    + " sum()")
                    .strongReference(true)
                    .register(registry);
            failure(code, "not_yet");
            for (SettlementReportCollector.FailureOutcome outcome :
                    SettlementReportCollector.FailureOutcome.values()) {
                failure(code, outcome.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    @Override
    public void notReceived(String sourceCode, String outcome) {
        failure(sourceCode, outcome).increment();
    }

    private Counter failure(String sourceCode, String outcome) {
        return Counter.builder(PULL_FAILURE)
                .tag("source", sourceCode)
                .tag("outcome", outcome)
                .description(
                        "Settlement pulls that brought no delivery, per source and outcome: not"
                                + " yet published, unavailable, timed out, broken transport or"
                                + " a refused answer - each retried as the pull permit paces"
                                + " it. A count, never an amount")
                .register(registry);
    }

    private double silenceOf(String sourceCode) {
        Optional<Map<String, Optional<Instant>>> reading = reading().bySource();
        if (reading.isEmpty()) {
            return Double.NaN;
        }
        Optional<Instant> last = reading.get().getOrDefault(sourceCode, Optional.empty());
        if (last.isEmpty()) {
            return Double.NaN;
        }
        // Recomputed at every scrape from the cached instant, so it climbs between refreshes.
        return Math.max(Duration.between(last.get(), clock.instant()).toSeconds(), 0);
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            Map<UUID, Instant> latest = batches.lastAcceptedAt(connection);
            Map<String, Optional<Instant>> bySource = new HashMap<>();
            for (SettlementFileStore.SourceRow source : files.sources(connection)) {
                bySource.put(source.code(), Optional.ofNullable(latest.get(source.id())));
            }
            fresh = new Cached(clock.instant(), Optional.of(bySource));
        } catch (Exception unreadable) {
            log.warn(
                    "Could not read the settlement sources' latest acceptances; the silence"
                            + " gauges report absent rather than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken - per instance, deciding nothing. */
    private record Cached(Instant takenAt, Optional<Map<String, Optional<Instant>>> bySource) {

        static Cached empty() {
            return new Cached(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
