package com.finapp.app.risk;

import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredTransactionStore;
import com.finapp.risk.MonitoringObserver;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RuleKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The monitoring intake's meters (`P13-TSK-019`; {@code PHASE_13_PLAN.md} section 15; bnpl's {@code BnplTermsMetrics}
 * and {@code BnplOriginationMetrics}), every series registered eagerly so a fresh instance publishes it from startup,
 * and no tag ever an id, an amount or a name ({@code INV-RSK-08}):
 *
 * <ul>
 *   <li>{@code finapp.risk.monitoring.lag{source}} - journal entries beyond the watermark, counted up to a million
 *       (saturating); NaN when unreadable, never zero for a read that failed. Read from the database behind a 15 s
 *       floor, so every instance reports the same figure: aggregate with {@code max()}, never {@code sum()}.</li>
 *   <li>{@code finapp.risk.monitoring.unclassified} - monitored transactions recorded {@code UNCLASSIFIED}; above 0
 *       is the alert (`-024` names the rule; `-015` raises the alert row).</li>
 *   <li>{@code finapp.risk.sweeper.claim.failed{kind=monitoring}} - a batch that failed and rolled back (the Phase
 *       12 to 13 gate's N5: every claim failure metered from the first task); the permit is untouched and a later tick
 *       claims again. Alert on any increase.</li>
 * </ul>
 *
 * <p><strong>The rule leg's</strong> (`P13-TSK-020`, {@link MonitoringObserver}): {@code finapp.risk.monitoring.subject
 * {outcome}} - every party a run met, {@code judged}, {@code failed} (its leg rolled back alone; alert on any increase)
 * or {@code not_judged} (no {@code MONITORING} version {@code ACTIVE}); {@code finapp.risk.monitoring.unplaced} -
 * customer accounts no party could be placed behind, unjudged; {@code finapp.risk.monitoring.latency} - one party's
 * leg, the head's lock wait included, beside {@code evaluation.latency} (M3's split trigger, measured by the storm);
 * {@code finapp.risk.rule.hit{operation=MONITORING,rule}} - each fired kind once per profile recompute. A failed leg
 * writes one line: the cause's class, never the party.
 */
@Slf4j
public final class RiskMonitoringMetrics implements MonitoringObserver {

    /** Exported as {@code finapp_risk_monitoring_subject_total}. */
    public static final String SUBJECT = "finapp.risk.monitoring.subject";

    /** Exported as {@code finapp_risk_monitoring_unplaced_total}. */
    public static final String UNPLACED = "finapp.risk.monitoring.unplaced";

    /** Exported as {@code finapp_risk_monitoring_latency_seconds}. */
    public static final String LATENCY = "finapp.risk.monitoring.latency";

    /** Exported as {@code finapp_risk_monitoring_lag}. */
    public static final String LAG = "finapp.risk.monitoring.lag";

    /** Exported as {@code finapp_risk_monitoring_unclassified}. */
    public static final String UNCLASSIFIED = "finapp.risk.monitoring.unclassified";

    /** Exported as {@code finapp_risk_sweeper_claim_failed_total}. */
    public static final String CLAIM_FAILED = "finapp.risk.sweeper.claim.failed";

    /** The sweeper kind this task meters; `-017`'s action sweep adds {@code action}. */
    public static final String KIND = "monitoring";

    /** The lag's saturation: beyond this the figure says "many", not how many. */
    static final long LAG_CAP = 1_000_000L;

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final MonitoringWatermarkStore watermarks;
    private final MonitoredTransactionStore transactions;
    private final MonitoredLedger ledger;
    private final Connections connections;
    private final Clock clock;
    private final Counter claimFailures;
    private final Map<SubjectOutcome, Counter> subjects = new EnumMap<>(SubjectOutcome.class);
    private final Counter unplaced;
    private final Timer latency;
    private final Map<RuleKind, Counter> hits = new EnumMap<>(RuleKind.class);
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Figures(long lag, long unclassified) {}

    private record Cached(Instant takenAt, Optional<Figures> figures) {}

    public RiskMonitoringMetrics(MonitoringWatermarkStore watermarks, MonitoredTransactionStore transactions,
            MonitoredLedger ledger, Connections connections, Clock clock, MeterRegistry registry) {
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        Gauge.builder(LAG, this, self -> self.refreshed().figures().map(f -> (double) f.lag()).orElse(Double.NaN))
                .tag("source", MonitoringWatermark.LEDGER_JOURNAL)
                .description("Journal entries beyond the monitoring watermark, up to a million. NaN when unreadable,"
                        + " never zero. Fleet-wide from every instance: aggregate with max(), never sum(). Alert when"
                        + " growing for an hour")
                .strongReference(true)
                .register(registry);
        Gauge.builder(UNCLASSIFIED, this,
                        self -> self.refreshed().figures().map(f -> (double) f.unclassified()).orElse(Double.NaN))
                .description("Monitored transactions recorded UNCLASSIFIED - a posting-key namespace the register does"
                        + " not know. NaN when unreadable. Aggregate with max(). Alert above 0")
                .strongReference(true)
                .register(registry);
        this.claimFailures = Counter.builder(CLAIM_FAILED)
                .description("Monitoring sweep batches that failed and rolled back - the watermark untouched, a later"
                        + " tick claims again. Alert on any increase")
                .tag("kind", KIND)
                .register(registry);
        for (SubjectOutcome outcome : SubjectOutcome.values()) {
            subjects.put(outcome, Counter.builder(SUBJECT)
                    .description("Parties the monitoring rule leg met, by what became of each: judged, failed (rolled"
                            + " back alone - alert on any increase) or not_judged (no MONITORING version ACTIVE)")
                    .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry));
        }
        this.unplaced = Counter.builder(UNPLACED)
                .description("Customer accounts the monitoring rule leg could place behind no party - unjudged")
                .register(registry);
        this.latency = Timer.builder(LATENCY)
                .description("One party's monitoring rule leg, the profile head's lock wait included - beside"
                        + " evaluation.latency (M3's split trigger)")
                // Buckets in the T-eval budget's neighbourhood: p50/p99 by histogram_quantile, max the timer's own.
                .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                        Duration.ofMillis(250), Duration.ofMillis(1_000), Duration.ofMillis(5_000))
                .register(registry);
        for (RuleKind kind : RuleKind.values()) {
            if (kind.appliesTo(RiskOperation.MONITORING)) {
                hits.put(kind, Counter.builder(RiskEvaluationMetrics.RULE_HIT)
                        .description("Fired risk rules, by operation and rule kind")
                        .tags("operation", RiskOperation.MONITORING.name(), "rule", kind.name())
                        .register(registry));
            }
        }
    }

    /** One batch that failed and rolled back. */
    public void claimFailed() {
        claimFailures.increment();
    }

    @Override
    public void subject(SubjectOutcome outcome) {
        subjects.get(outcome).increment();
    }

    @Override
    public void subjectFailed(Class<? extends Throwable> failed) {
        // The cause's class name only - never its message (a storage failure's can quote a row), never the party.
        String cause = failed.getSimpleName();
        log.warn("Risk monitoring subject failed: {}", cause);
    }

    @Override
    public void unplaced(int accounts) {
        unplaced.increment(accounts);
    }

    @Override
    public void judged(Duration leg) {
        latency.record(leg);
    }

    @Override
    public void fired(Set<RuleKind> fired) {
        for (RuleKind kind : fired) {
            Counter counter = hits.get(kind);
            if (counter != null) {
                counter.increment();
            }
        }
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            MonitoringWatermark watermark = watermarks.read(connection, MonitoringWatermark.LEDGER_JOURNAL)
                    .orElseThrow(() -> new IllegalStateException("the ledger journal's watermark row is missing"));
            long lag = ledger.entriesBeyond(connection, watermark.lastEntryId(), LAG_CAP);
            long unclassified = transactions.countUnclassified(connection);
            fresh = new Cached(clock.instant(), Optional.of(new Figures(lag, unclassified)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The monitoring figures could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
