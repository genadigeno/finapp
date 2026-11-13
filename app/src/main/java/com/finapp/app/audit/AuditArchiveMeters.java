package com.finapp.app.audit;

import com.finapp.platform.audit.archive.AuditArchival;
import com.finapp.platform.audit.archive.AuditArchiveSegments;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * The audit archive's meters (`P16-TSK-010`), registered eagerly on every instance whether or not it archives, so a rule has a series
 * on each:
 *
 * <ul>
 *   <li>{@code finapp.audit.archive.segments{outcome}}: each attempt that held a segment, by how it ended ({@code archived},
 *       {@code store_unavailable}, {@code mismatch}, {@code fenced}, {@code refused}), and {@code failed} for an attempt the database
 *       ended. An attempt that found nothing to do is not counted.
 *   <li>{@code finapp.audit.archive.rows}: rows in the segments this instance archived.
 *   <li>{@code finapp.audit.archive.pending}: audit rows no segment holds yet, counted up to {@value #PENDING_CAP} (a reading of the
 *       cap means at least that many), and {@code finapp.audit.archive.pending.oldest}: how long the oldest has waited, in seconds on
 *       the database's clock, zero when none waits. Fleet-wide from every instance: aggregate with {@code max()}, never {@code sum()}.
 *       No value when the database cannot be read.
 *   <li>{@code finapp.audit.archive.enabled}: 1 where this instance archives (a store is configured), 0 where it does not.
 * </ul>
 *
 * <p>The two gauges read the database, off the scrape thread like every {@code finapp.} gauge ({@code OffScrapeMeterRegistry}). No
 * identifier and no value reaches a tag: {@code outcome} is this class's closed vocabulary.
 */
@Slf4j
public final class AuditArchiveMeters {

    static final String SEGMENTS = "finapp.audit.archive.segments";
    static final String ROWS = "finapp.audit.archive.rows";
    static final String PENDING = "finapp.audit.archive.pending";
    static final String OLDEST = "finapp.audit.archive.pending.oldest";
    static final String ENABLED = "finapp.audit.archive.enabled";

    /** The most queued rows the pending gauge counts: a bound on what one reading scans. */
    static final int PENDING_CAP = 100_001;

    static final String FAILED = "failed";

    private final Map<AuditArchival.Outcome, Counter> outcomes = new EnumMap<>(AuditArchival.Outcome.class);
    private final Counter failed;
    private final Counter rows;

    public AuditArchiveMeters(MeterRegistry registry, AuditArchiveSegments segments, boolean enabled) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(segments, "segments");
        for (AuditArchival.Outcome outcome : AuditArchival.Outcome.values()) {
            if (outcome != AuditArchival.Outcome.IDLE) {
                outcomes.put(outcome, segments(registry, outcome.name().toLowerCase(Locale.ROOT)));
            }
        }
        this.failed = segments(registry, FAILED);
        this.rows = Counter.builder(ROWS).description("Audit rows in the segments this instance archived").register(registry);
        Gauge.builder(PENDING, () -> pending(segments))
                .description("Audit rows no archive segment holds yet, counted up to " + PENDING_CAP
                        + ". Fleet-wide from every instance: aggregate with max(), never sum()")
                .register(registry);
        Gauge.builder(OLDEST, () -> oldestSeconds(segments))
                .description("How long the oldest audit row no archive segment holds has waited, in whole seconds on the database's"
                        + " clock; 0 when none waits. Fleet-wide from every instance: aggregate with max(), never sum()")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(ENABLED, () -> enabled ? 1 : 0)
                .description("1 where this instance archives the audit trail (finapp.audit.archive.endpoint is set), 0 where it does not")
                .register(registry);
    }

    private static Counter segments(MeterRegistry registry, String outcome) {
        return Counter.builder(SEGMENTS)
                .tag("outcome", outcome)
                .description("Audit archive attempts that held a segment, by how they ended")
                .register(registry);
    }

    /** One attempt's outcome; an idle one is not counted. */
    void record(AuditArchival.Result result) {
        Counter counter = outcomes.get(result.outcome());
        if (counter != null) {
            counter.increment();
        }
        if (result.outcome() == AuditArchival.Outcome.ARCHIVED) {
            // One by one: a row count, never an amount, and no floating point in this class (INV-MON-01's rule).
            for (int row = 0; row < result.rows(); row++) {
                rows.increment();
            }
        }
    }

    /** An attempt the database ended in a way no outcome names. */
    void failed() {
        failed.increment();
    }

    /** The queued rows, counted up to the cap; {@code null}, which the gauge reports as no value, when the database cannot be read. */
    private static Long pending(AuditArchiveSegments segments) {
        AuditArchiveSegments.Backlog backlog = read(segments);
        return backlog == null ? null : backlog.pending();
    }

    /** The oldest queued row's wait in whole seconds; {@code null}, no value, when the database cannot be read. */
    private static Long oldestSeconds(AuditArchiveSegments segments) {
        AuditArchiveSegments.Backlog backlog = read(segments);
        return backlog == null ? null : backlog.oldest().toSeconds();
    }

    private static AuditArchiveSegments.Backlog read(AuditArchiveSegments segments) {
        try {
            return segments.backlog(PENDING_CAP);
        } catch (RuntimeException failure) {
            // No value, never a zero: an unread backlog must not look like an empty one. The class only.
            log.warn("Could not read the audit archive's backlog for metrics: {}", failure.getClass().getSimpleName());
            return null;
        }
    }
}
