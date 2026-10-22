package com.finapp.app.risk;

import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredTransactionStore;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.MonitoringWatermarkStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
 */
@Slf4j
public final class RiskMonitoringMetrics {

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
    }

    /** One batch that failed and rolled back. */
    public void claimFailed() {
        claimFailures.increment();
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
