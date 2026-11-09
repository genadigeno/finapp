package com.finapp.app.accounting;

import com.finapp.accounting.JournalCensusWalk;
import com.finapp.accounting.SourceCensusStore;
import com.finapp.accounting.SourceFindingKind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * The source-join census's gauges (`P15-TSK-035`), off the scrape thread like every {@code finapp.} gauge (`P15-TSK-024`), read with the
 * accounting gauges' statement timeout on a read-only snapshot, and <strong>no value</strong> on any failed read.
 *
 * <ul>
 *   <li>{@code finapp.accounting.source.census.breaks{kind}}: the findings within the page's standing window. <strong>A standing finding
 *       is published whether or not the census is healthy</strong>: a transient failed batch must not read as NaN and let a live page
 *       resolve. Zero is published only by a healthy census; an unhealthy one with nothing standing reads no value, never a comforting zero.
 *   <li>{@code finapp.accounting.source.census.findings}: the findings within the ticket's longer window, so the page's end is not
 *       the end of anyone being told.
 *   <li>{@code finapp.accounting.source.census.lag.seconds}: how long something has waited, the greater of the time since the head last
 *       moved and the age of the oldest entry waiting. <strong>Published while the census is unhealthy</strong>: a stalled walk must be
 *       able to page. Zero while nothing waits.
 * </ul>
 */
public final class SourceCensusMetrics {

    /** Spelled with dots: exported as {@code finapp_accounting_source_census_breaks}. */
    public static final String BREAKS = "finapp.accounting.source.census.breaks";

    /** Spelled with dots: exported as {@code finapp_accounting_source_census_findings}. */
    public static final String FINDINGS = "finapp.accounting.source.census.findings";

    /** Spelled with dots: exported as {@code finapp_accounting_source_census_lag_seconds}. */
    public static final String LAG = "finapp.accounting.source.census.lag.seconds";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** One reading: what stands, what was found recently, the lag and whether the census may be believed. */
    record Reading(Map<SourceFindingKind, Long> standing, long recent, long lagSeconds, boolean healthy) {
    }

    private final AccountingGaugeRead<Reading> read;

    public SourceCensusMetrics(SourceCensusStore store, JournalCensusWalk walk, Duration standing, Duration recent, Duration staleAfter,
            GlSnapshots snapshots, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(walk, "walk");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(recent, "recent");
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (standing.isNegative() || standing.isZero()) {
            throw new IllegalArgumentException("a finding stands for a positive time, or it never pages");
        }
        if (recent.compareTo(standing) <= 0) {
            throw new IllegalArgumentException("the ticket's window is longer than the page's, or the page's end tells nobody");
        }
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleness is a positive bound");
        }
        this.read = new AccountingGaugeRead<>("The source-join census", snapshots, clock, MIN_REFRESH, connection -> {
            SourceCensusStore.Reading reading = store.read(connection, standing, recent, staleAfter);
            return new Reading(reading.standing(), reading.recent(), lagSeconds(connection, walk, reading), reading.healthy());
        });
        for (SourceFindingKind kind : SourceFindingKind.values()) {
            Gauge.builder(BREAKS, () -> breaks(kind))
                    .tag("kind", kind.name())
                    .description("Journal entries the source-join census found with no record behind them, or whose record differs, standing within the page's window."
                            + " Published while any stand even if the census is unhealthy; no value when none stand and it cannot be believed. Fleet-wide from every instance: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(FINDINGS, this::recent)
                .description("Journal entries the source-join census counted within the ticket's longer window. No value when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(LAG, this::lagSeconds)
                .description("Seconds something has waited for the census: the greater of the time since the head moved and the age of the oldest waiting entry, while one waits."
                        + " Published while the census is unhealthy, so a stalled walk can page. No value when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** {@code kind}'s standing findings; {@code null} - no value - when unreadable, or when none stand and the census cannot be believed. */
    Long breaks(SourceFindingKind kind) {
        Reading reading = read.current();
        if (reading == null) {
            return null;
        }
        long standing = reading.standing().getOrDefault(kind, 0L);
        return standing > 0 || reading.healthy() ? Long.valueOf(standing) : null;
    }

    /** Findings within the ticket's window; {@code null} - no value - when unreadable. */
    Long recent() {
        Reading reading = read.current();
        return reading == null ? null : reading.recent();
    }

    /** The lag in seconds; {@code null} - no value - when unreadable. */
    Long lagSeconds() {
        Reading reading = read.current();
        return reading == null ? null : reading.lagSeconds();
    }

    /** The greater of how long the head has stood and how old the waiting entry is: nothing waiting reads as zero. */
    private static long lagSeconds(Connection connection, JournalCensusWalk walk, SourceCensusStore.Reading reading) {
        return reading.position().flatMap(position -> walk.oldestAfter(connection, position))
                .map(oldest -> Math.max(0L, Math.max(Duration.between(oldest, reading.asOf()).toSeconds(),
                        Duration.between(reading.advancedAt(), reading.asOf()).toSeconds())))
                .orElse(0L);
    }
}
