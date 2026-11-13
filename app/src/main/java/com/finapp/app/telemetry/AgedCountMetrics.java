package com.finapp.app.telemetry;

import com.finapp.reconciliation.AgedReadings;
import com.finapp.reconciliation.Severity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The break-age counts (`P16-TSK-029`, {@code THREAT_MODEL.md} §6 item 42 (a), decided (i)): {@code finapp.reconciliation.break.aged}
 * per severity - the open breaks older than that severity's bound - and {@code finapp.reconciliation.suspense.aged} - the suspense
 * items holding a remainder older than thirty days.
 *
 * <p>Why counts. {@code finapp.reconciliation.break.age} and {@code .suspense.age} are the age of the OLDEST, so while one parked item
 * stands (runbook §11.11) its rule stays firing on it and a newly aged break or item behind it raises nothing. A count rises when a
 * second one crosses its bound, and the rules beside today's fire on that rise ({@code settlement-reconciliation.yml}).
 *
 * <p>The bounds are the age rules' thresholds ({@link #BREAK_BOUNDS}, {@link #SUSPENSE_BOUND}), held equal to them by
 * {@code AgedCountMetricsTest} reading the rule file. An item is aged when the whole-second age the age gauges publish exceeds its
 * bound, measured from this instance's clock as theirs are: a break raised at or before {@code now - (bound + 1 s)}, a suspense item
 * opened on or before the date of {@code now - (30 d + 1 s)} (its age runs from midnight UTC of {@code opened_on}). So a count of at
 * least one is exactly today's rule's expression holding.
 *
 * <p>The {@code BreakMetrics} shape: every series eager, one reading per refresh floor shared by all five, NaN on every series when
 * the reading fails, never a comforting zero; 0 when nothing is aged. Counts only, never an identifier or a value at issue
 * (ADR-0072). Per instance, deciding nothing; fleet-wide, aggregate with {@code max()}.
 */
@Slf4j
public final class AgedCountMetrics {

    public static final String BREAK_AGED = "finapp.reconciliation.break.aged";
    public static final String SUSPENSE_AGED = "finapp.reconciliation.suspense.aged";

    /** Each severity's bound: {@code BreakAgeCritical} &gt; 0, {@code BreakAgeHigh} &gt; 1 d, {@code BreakAgeMedium} &gt; 5 d, {@code BreakAgeLow} &gt; 15 d. */
    static final Map<Severity, Duration> BREAK_BOUNDS = Map.of(
            Severity.CRITICAL, Duration.ZERO,
            Severity.HIGH, Duration.ofDays(1),
            Severity.MEDIUM, Duration.ofDays(5),
            Severity.LOW, Duration.ofDays(15));

    /** {@code SuspenseItemAged}'s bound: thirty days (INV-REC-05: suspense is never a resting place). */
    static final Duration SUSPENSE_BOUND = Duration.ofDays(30);

    /** The shared refresh floor - {@code BreakMetrics}'. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The age gauges publish whole seconds, so "past the bound" is at least one second more. */
    private static final Duration WHOLE_SECOND = Duration.ofSeconds(1);

    private final AgedReadings readings;
    private final ReconciliationMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());

    public AgedCountMetrics(
            AgedReadings readings, ReconciliationMetrics.Connections connections, Clock clock, MeterRegistry registry) {
        this.readings = Objects.requireNonNull(readings, "readings must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        for (Severity severity : Severity.values()) {
            Gauge.builder(BREAK_AGED, this, self -> self.agedBreaks(severity))
                    .tag("severity", severity.name().toLowerCase(Locale.ROOT))
                    .description("Open reconciliation breaks of this severity older than its bound (CRITICAL 0, HIGH 1 d, MEDIUM 5 d,"
                            + " LOW 15 d), from their stored raise: alerted when it rises while one stands. A count, never an"
                            + " amount. 0 when none; NaN when unreadable. Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(SUSPENSE_AGED, this, AgedCountMetrics::agedSuspense)
                .description("Suspense items holding a remainder opened over 30 days ago (INV-REC-05): alerted when it rises while"
                        + " one stands. A count, never an amount. 0 when none; NaN when unreadable. Fleet-wide: aggregate with max(),"
                        + " never sum()")
                .strongReference(true)
                .register(registry);
    }

    private double agedBreaks(Severity severity) {
        return reading().counts().map(counts -> (double) counts.breaks().getOrDefault(severity, 0L)).orElse(Double.NaN);
    }

    private double agedSuspense() {
        return reading().counts().map(counts -> (double) counts.suspense()).orElse(Double.NaN);
    }

    /** Each severity's cutoff at {@code now}: raised at or before it, a break is past its bound. */
    static Map<Severity, Instant> breakCutoffs(Instant now) {
        Map<Severity, Instant> cutoffs = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            cutoffs.put(severity, now.minus(BREAK_BOUNDS.get(severity)).minus(WHOLE_SECOND));
        }
        return cutoffs;
    }

    /** The suspense cutoff at {@code now}: opened on or before this date, an item is past its bound. */
    static LocalDate suspenseCutoff(Instant now) {
        return LocalDate.ofInstant(now.minus(SUSPENSE_BOUND).minus(WHOLE_SECOND), ZoneOffset.UTC);
    }

    private Cached reading() {
        Cached current = cached.get();
        Instant now = clock.instant();
        if (!current.isStaleAt(now)) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            Map<Severity, Long> breaks = readings.agedBreaks(connection, breakCutoffs(now));
            long suspense = readings.agedSuspense(connection, suspenseCutoff(now));
            fresh = new Cached(now, Optional.of(new Counts(breaks, suspense)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("What is past its bound could not be counted; the aged-count gauges read NaN: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(now, Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private record Counts(Map<Severity, Long> breaks, long suspense) {}

    /** One reading and when it was taken - per instance, deciding nothing. */
    private record Cached(Instant takenAt, Optional<Counts> counts) {

        static Cached empty() {
            return new Cached(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
