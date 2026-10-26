package com.finapp.app.risk;

import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.RiskProofReads;
import com.finapp.risk.RiskProofs;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * How long risk's two machines have left work standing - in TIME, on the database's clock (`P13-TSK-024`, the review's
 * IMPORTANT 1 and 2; {@code PHASE_13_PLAN.md} section 15; {@code RescreeningMetrics}' pattern):
 *
 * <ul>
 *   <li>{@code finapp.risk.monitoring.age{source}} - seconds since the oldest journal entry beyond the monitoring
 *       watermark was posted (its UUIDv7 dates it), 0 when none stands beyond it. The lag gauge counts entries, which a
 *       busy ledger keeps above zero and a quiet one keeps near zero however stalled the sweep - this does not: a busy
 *       but draining sweep holds it within the settle horizon and a poll, a stalled one (every sweeper off, its claim
 *       failing, a head held by a stuck transaction holding the watermark) lets it grow at any volume.
 *       {@code RiskMonitoringLagging} alerts past an hour.</li>
 *   <li>{@code finapp.risk.report.unconcluded.age} - seconds since the oldest regulatory report still {@code FILED} was
 *       filed, 0 when none is: a filing the authority has not concluded - answered {@code UNKNOWN}, its calls failing,
 *       or its drive not running. {@code RiskFilingUnknown} alerts past two inquiry intervals.</li>
 * </ul>
 *
 * <p>Registered eagerly; <strong>never read on the scrape thread</strong>: a scrape past the floor starts one reading
 * on the refresher (single-flight per instance) and answers the last figures meanwhile; the reading is three bounded
 * statements on one read-only snapshot ({@link #over}); a failed reading, or figures past two floors, are NaN - never 0.
 * Every instance reads the same figures: aggregate with {@code max()}. A per-instance cache of derived figures, never
 * truth and never an input to a decision; no id, party or amount reaches a tag ({@code INV-RSK-08}).
 */
@Slf4j
public final class RiskAgeMetrics {

    /** Exported as {@code finapp_risk_monitoring_age}. */
    public static final String MONITORING_AGE = "finapp.risk.monitoring.age";

    /** Exported as {@code finapp_risk_report_unconcluded_age}. */
    public static final String FILING_AGE = "finapp.risk.report.unconcluded.age";

    /** The refresh floor - the monitoring meters' 15 s. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** Past this age the figures are NaN: two floors - the refresher did not finish a reading in time. */
    static final Duration STALE_AFTER = MIN_REFRESH.multipliedBy(2);

    /** The two ages, in whole seconds. */
    public record Ages(long monitoringSeconds, long filingSeconds) {
        public Ages {
            if (monitoringSeconds < 0 || filingSeconds < 0) {
                throw new IllegalArgumentException("an age counts from zero");
            }
        }
    }

    /** The journal's first entry beyond a position - {@code LedgerMonitoredLedger.firstBeyond}, the composition's. */
    @FunctionalInterface
    public interface FirstBeyond {
        Optional<UUID> of(Connection snapshot, UUID afterEntryId);
    }

    private record Cached(Instant takenAt, Optional<Ages> ages) {}

    private final Supplier<Ages> ages;
    private final Executor refresher;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /**
     * The ages read on one read-only snapshot of {@code transactions}, every statement bounded by
     * {@code statementTimeout}: the database's clock, the watermark, the first entry beyond it, the oldest filing.
     */
    public static Supplier<Ages> over(MonitoringWatermarkStore watermarks, FirstBeyond journal, RiskProofReads reads,
            RiskTransactions transactions, Duration statementTimeout) {
        Objects.requireNonNull(watermarks, "watermarks");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(reads, "reads");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        return () -> transactions.inSnapshot(snapshot -> {
            RiskProofs.bound(snapshot, statementTimeout);
            Instant now = reads.databaseNow(snapshot);
            MonitoringWatermark watermark = watermarks.read(snapshot, MonitoringWatermark.LEDGER_JOURNAL)
                    .orElseThrow(() -> new IllegalStateException("the ledger journal's watermark row is missing"));
            return ages(now, journal.of(snapshot, watermark.lastEntryId()), reads.oldestUnconcludedFiling(snapshot));
        });
    }

    /**
     * The two ages at the database's {@code now}: the first unmonitored entry's - dated by its UUIDv7's millisecond - and
     * the oldest unconcluded filing's; 0 for none.
     */
    static Ages ages(Instant now, Optional<UUID> firstUnmonitored, Optional<Instant> oldestFiled) {
        long monitoring = firstUnmonitored
                .map(first -> secondsSince(now, Instant.ofEpochMilli(MonitoringWatermark.sequenceOf(first))))
                .orElse(0L);
        long filing = oldestFiled.map(filed -> secondsSince(now, filed)).orElse(0L);
        return new Ages(monitoring, filing);
    }

    /** A refresher that runs each reading on a virtual thread of its own - the composition's. */
    public static Executor virtualThreads() {
        return reading -> Thread.ofVirtual().name("risk-ages").start(reading);
    }

    /** Whole seconds from {@code since} to {@code now}, never negative (an instance's clock minted the journal's id). */
    static long secondsSince(Instant now, Instant since) {
        return Math.max(0L, Duration.between(since, now).toSeconds());
    }

    public RiskAgeMetrics(Supplier<Ages> ages, Executor refresher, Clock clock, MeterRegistry registry) {
        this.ages = Objects.requireNonNull(ages, "ages");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        // Each age through the ToDoubleFunction Micrometer's Gauge imposes - unboxed, NaN the sentinel for unreadable
        // (RescreeningMetrics' shape; NoFloatingPointMoneyRulesTest holds the class to no double-typed member).
        register(Gauge.builder(MONITORING_AGE, this, self -> {
                    Optional<Ages> figures = self.figure();
                    return figures.isPresent() ? (double) figures.get().monitoringSeconds() : Double.NaN;
                })
                .tag("source", MonitoringWatermark.LEDGER_JOURNAL)
                .description("Seconds since the oldest journal entry beyond the monitoring watermark was posted - 0 when"
                        + " none stands beyond it. NaN when unreadable or stale. Fleet-wide: max(). Alert past an hour"),
                registry);
        register(Gauge.builder(FILING_AGE, this, self -> {
                    Optional<Ages> figures = self.figure();
                    return figures.isPresent() ? (double) figures.get().filingSeconds() : Double.NaN;
                })
                .description("Seconds since the oldest regulatory report still FILED was filed - 0 when none is. NaN when"
                        + " unreadable or stale. Fleet-wide: max(). Alert past two inquiry intervals"),
                registry);
    }

    private static void register(Gauge.Builder<RiskAgeMetrics> gauge, MeterRegistry registry) {
        gauge.strongReference(true).register(registry);
    }

    /** The ages served: the last reading's, empty when it failed or is past {@link #STALE_AFTER} - the gauges' NaN. */
    private Optional<Ages> figure() {
        Cached current = refreshed();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(STALE_AFTER) >= 0) {
            return Optional.empty();
        }
        return current.ages();
    }

    /** The last figures; when they are past the floor, one more reading started on the refresher unless one runs. */
    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0
                || !inFlight.compareAndSet(false, true)) {
            return current;
        }
        try {
            refresher.execute(() -> {
                try {
                    cached.set(read());
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RuntimeException refused) {
            inFlight.set(false);
            log.warn("The risk ages' reading could not start: {}", refused.getClass().getSimpleName());
        }
        return cached.get();
    }

    private Cached read() {
        try {
            return new Cached(clock.instant(), Optional.of(ages.get()));
        } catch (RuntimeException unreadable) {
            log.warn("The risk ages could not be read: {}", unreadable.getClass().getSimpleName());
            return new Cached(clock.instant(), Optional.empty());
        }
    }
}
