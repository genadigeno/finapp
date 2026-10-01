package com.finapp.app.telemetry;

import com.finapp.reconciliation.BreakReadings;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.Severity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The open breaks as gauges (`P8-TSK-024`, `PHASE_8_PLAN.md` §15):
 * {@code finapp.reconciliation.break.open} per type and severity - the backlog - and
 * {@code finapp.reconciliation.break.age} per severity - the oldest open break's age in
 * seconds, what the per-severity alerts read (CRITICAL > 0 h, HIGH > 1 d, MEDIUM > 5 d,
 * LOW > 15 d).
 *
 * <p>The {@code ReconciliationMetrics} shape: every series eager (all 14 types by 4
 * severities, and the 4 ages), one GROUP BY per refresh floor shared by every gauge, NaN when
 * the database is unreadable and never zero, 0 when nothing is open. Counts and ages only,
 * never a value at issue (ADR-0072). Per instance, deciding nothing; fleet-wide, aggregate with
 * {@code max()}. No base unit, so the exposed name is the series' own (`P0-TSK-029`'s finding).
 */
@Slf4j
public final class BreakMetrics {

    public static final String BREAK_OPEN = "finapp.reconciliation.break.open";
    public static final String BREAK_AGE = "finapp.reconciliation.break.age";

    /** The shared refresh floor - {@code ReconciliationMetrics}'. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final BreakReadings readings;
    private final ReconciliationMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    public BreakMetrics(
            BreakReadings readings,
            ReconciliationMetrics.Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.readings = Objects.requireNonNull(readings, "readings must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        for (Severity severity : Severity.values()) {
            String label = severity.name().toLowerCase(Locale.ROOT);
            for (BreakType type : BreakType.values()) {
                Gauge.builder(BREAK_OPEN, this, self -> self.open(type, severity))
                        .tag("type", type.name().toLowerCase(Locale.ROOT))
                        .tag("severity", label)
                        .description(
                                "Open reconciliation breaks by type and severity - the backlog."
                                        + " A count, never an amount. NaN when unreadable,"
                                        + " never zero. Fleet-wide: aggregate with max(), never"
                                        + " sum()")
                        .strongReference(true)
                        .register(registry);
            }
            Gauge.builder(BREAK_AGE, this, self -> self.ageSeconds(severity))
                    .tag("severity", label)
                    .description(
                            "Age in seconds of the oldest open break of this severity, from its"
                                    + " stored raise - alerted per severity. 0 when none is"
                                    + " open; NaN when unreadable. Fleet-wide: aggregate with"
                                    + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double open(BreakType type, Severity severity) {
        return reading()
                .rows()
                .map(rows -> (double) rows.stream()
                        .filter(row -> row.type() == type && row.severity() == severity)
                        .mapToLong(BreakReadings.OpenBreaks::count)
                        .sum())
                .orElse(Double.NaN);
    }

    private double ageSeconds(Severity severity) {
        return reading()
                .rows()
                .map(rows -> rows.stream()
                        .filter(row -> row.severity() == severity)
                        .map(BreakReadings.OpenBreaks::oldestRaisedAt)
                        .min(Instant::compareTo)
                        .map(oldest -> (double) Math.max(
                                0L, Duration.between(oldest, clock.instant()).getSeconds()))
                        .orElse(0.0d))
                .orElse(Double.NaN);
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            fresh = new Cached(clock.instant(), Optional.of(readings.openBreaks(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn(
                    "The open breaks could not be read; the break gauges read NaN: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    /** One reading and when it was taken - per instance, deciding nothing. */
    private record Cached(Instant takenAt, Optional<List<BreakReadings.OpenBreaks>> rows) {

        static Cached empty() {
            return new Cached(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
