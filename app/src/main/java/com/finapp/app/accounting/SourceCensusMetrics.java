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
 * The source-join census's gauges (`P15-TSK-035`): {@code finapp.accounting.source.census.breaks{kind}} - the findings standing within
 * the page's standing window - and {@code finapp.accounting.source.census.lag.seconds} - how far behind the journal the walk is.
 * Off the scrape thread like every {@code finapp.} gauge (`P15-TSK-024`), read with the accounting gauges' statement timeout on a
 * read-only snapshot, and {@link AccountingGaugeRead} publishes <strong>no value</strong> on any failed read. So does a census that
 * has not run recently or whose last run failed: {@link Reading} answers null - a zero here would read as a clean journal.
 */
public final class SourceCensusMetrics {

    /** Spelled with dots: exported as {@code finapp_accounting_source_census_breaks}. */
    public static final String BREAKS = "finapp.accounting.source.census.breaks";

    /** Spelled with dots: exported as {@code finapp_accounting_source_census_lag_seconds}. */
    public static final String LAG = "finapp.accounting.source.census.lag.seconds";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** One reading: what stands, and the lag; a null reading when the census may not be believed. */
    record Reading(Map<SourceFindingKind, Long> standing, long lagSeconds) {
    }

    private final AccountingGaugeRead<Reading> read;

    public SourceCensusMetrics(SourceCensusStore store, JournalCensusWalk walk, Duration standing, Duration staleAfter, GlSnapshots snapshots,
            Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(walk, "walk");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (standing.isNegative() || standing.isZero()) {
            throw new IllegalArgumentException("a finding stands for a positive time, or it never pages");
        }
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleness is a positive bound");
        }
        this.read = new AccountingGaugeRead<>("The source-join census", snapshots, clock, MIN_REFRESH, connection -> {
            SourceCensusStore.Reading reading = store.read(connection, standing, staleAfter);
            if (!reading.healthy()) {
                return null;
            }
            return new Reading(reading.standing(), lagSeconds(connection, walk, reading));
        });
        for (SourceFindingKind kind : SourceFindingKind.values()) {
            Gauge.builder(BREAKS, () -> breaks(kind))
                    .tag("kind", kind.name())
                    .description("Journal entries the source-join census found with no record behind them, or whose record differs, standing within the window."
                            + " No value when the census cannot be believed. Fleet-wide from every instance: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(LAG, this::lagSeconds)
                .description("Seconds between the census's head and the journal's oldest unjudged entry, on the database's clock; rises while a long-open"
                        + " transaction holds the walk's fence. No value when the census cannot be believed. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The findings standing for {@code kind}; {@code null} - no value - when unreadable. */
    Long breaks(SourceFindingKind kind) {
        Reading reading = read.current();
        return reading == null ? null : reading.standing().getOrDefault(kind, 0L);
    }

    /** The walk's lag in seconds; {@code null} - no value - when unreadable. */
    Long lagSeconds() {
        Reading reading = read.current();
        return reading == null ? null : reading.lagSeconds();
    }

    private static long lagSeconds(Connection connection, JournalCensusWalk walk, SourceCensusStore.Reading reading) {
        return reading.position().flatMap(position -> walk.oldestAfter(connection, position))
                .map(oldest -> Math.max(0L, Duration.between(oldest, reading.asOf()).toSeconds()))
                .orElse(0L);
    }
}
