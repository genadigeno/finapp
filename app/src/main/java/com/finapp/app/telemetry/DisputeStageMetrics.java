package com.finapp.app.telemetry;

import com.finapp.payments.DisputeStage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.payments.dispute} (`P7-TSK-015`, {@code PHASE_7_PLAN.md} §15, ADR-0061's operational
 * impact): how many disputes stand at each stage now, tagged {@code stage} — every dispute's stage
 * visible on a running instance.
 *
 * <p><strong>By stage and outcome, in one tag</strong>: the open stages ({@code inquiry},
 * {@code charged_back}, {@code represented}) are the workload; the terminal ones ({@code won},
 * {@code lost}, {@code accepted}, {@code closed}) ARE the outcomes, and only ever grow — so a win
 * rate is {@code won / (won + lost)} and a dispute rate the growth of the total, read straight off
 * the series. A count per stage, never an identifier or an amount ({@code INV-AUD-02}); the
 * chargeback ratio per MERCHANT is an operator report, never a tag (ADR-0018).
 *
 * <p>The {@code DisputeDeadlineMetrics} stance verbatim: eager (every stage registered from the
 * enum at construction), NaN for every stage when unreadable and never a false zero, a refresh
 * floor as the rate limit, fleet-wide — aggregate with {@code max()}, never {@code sum()}. Per
 * instance and non-authoritative: it decides nothing, so it needs no
 * {@code DISTRIBUTED_EXECUTION.md} §3 row.
 */
@Slf4j
final class DisputeStageMetrics {

    /** {@code finapp.payments.dispute} — disputes by stage. */
    static final String DISPUTE = "finapp.payments.dispute";

    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** The {@code PaymentMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    interface Connections {
        Connection open() throws SQLException;
    }

    /** The dispute read seam ({@code DisputeStore.countByStage}). */
    @FunctionalInterface
    interface Counts {
        Map<DisputeStage, Long> read(Connection connection);
    }

    private final Counts counts;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    DisputeStageMetrics(Counts counts, Connections connections, Clock clock, MeterRegistry registry) {
        this.counts = Objects.requireNonNull(counts, "counts must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        for (DisputeStage stage : DisputeStage.values()) {
            Gauge.builder(DISPUTE, this, self -> self.reading().valueOrNaN(stage))
                    .tag("stage", stage.name().toLowerCase(Locale.ROOT))
                    .description(
                            "Disputes standing at each stage now: the open stages are the"
                                    + " workload, the terminal ones (won, lost, accepted, closed)"
                                    + " the outcomes, which only grow. A count, never an"
                                    + " identifier. NaN when unreadable, never zero. Fleet-wide:"
                                    + " aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private Cached reading() {
        Cached current = cached.get();
        Instant now = clock.instant();
        if (!current.isStaleAt(now)) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            fresh = new Cached(now, Map.copyOf(counts.read(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn(
                    "Could not count the disputes by stage; the gauges report absent rather than"
                            + " a false zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(now, null);
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken — per instance, non-authoritative; null when unreadable. */
    private record Cached(Instant takenAt, Map<DisputeStage, Long> byStage) {

        static Cached empty() {
            return new Cached(Instant.MIN, null);
        }

        boolean isStaleAt(Instant now) {
            return takenAt.isBefore(now.minus(MIN_REFRESH));
        }

        double valueOrNaN(DisputeStage stage) {
            if (byStage == null) {
                return Double.NaN;
            }
            Long count = byStage.get(stage);
            return count == null ? 0.0 : (double) count;
        }
    }
}
