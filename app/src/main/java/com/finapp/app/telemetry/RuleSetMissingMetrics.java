package com.finapp.app.telemetry;

import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.reconciliation.rule.set.missing{source}} (`P9-TSK-011`, PHASE_9_PLAN.md section
 * 12.9.2): 1 for a declared source with no {@code ACTIVE} rule set version, 0 otherwise, NaN when
 * unreadable - never zero for a read that failed. No migration seeds a new source's version 1
 * (D26), so until two controllers activate it the source's files wait {@code PARSED} with the accept
 * leg's backoff ({@code RuleSetMissing}); this gauge is what makes that wait loud, and it is
 * alerted. Registered eagerly per declared source (the register composed in app, so a source the
 * build declares has its series from startup); read from the database, so every instance reports
 * the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s, the
 * reconciliation gauges' floor.
 */
@Slf4j
public final class RuleSetMissingMetrics {

    /**
     * Spelled with dots - the platform's meter convention admits no underscore - so Prometheus
     * exports it as {@code finapp_reconciliation_rule_set_missing}, the plan's name exactly.
     */
    public static final String MISSING = "finapp.reconciliation.rule.set.missing";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final SettlementSources sources;
    private final SettlementFileStore<Connection> sourceRows;
    private final RuleSets ruleSets;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<String, Boolean>> missingByCode) {}

    public RuleSetMissingMetrics(
            SettlementSources sources,
            SettlementFileStore<Connection> sourceRows,
            RuleSets ruleSets,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.sources = sources;
        this.sourceRows = sourceRows;
        this.ruleSets = ruleSets;
        this.connections = connections;
        this.clock = clock;
        for (SettlementSourceDescriptor declared : sources.declared()) {
            String code = declared.code();
            Gauge.builder(MISSING, this, self -> self.missingOf(code))
                    .tag("source", code)
                    .description(
                            "1 when this declared settlement source has no ACTIVE rule set version -"
                                    + " its files wait PARSED and nothing it reports is matched until two"
                                    + " controllers activate its version 1 (RuleSetMissing). MUST read 0"
                                    + " and is alerted. NaN when unreadable, never zero. Fleet-wide from"
                                    + " every instance: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double missingOf(String code) {
        return refreshed().missingByCode()
                .map(missing -> missing.getOrDefault(code, false) ? 1.0 : 0.0)
                .orElse(Double.NaN);
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            Map<String, Boolean> missing = new HashMap<>();
            for (SettlementSourceDescriptor declared : sources.declared()) {
                missing.put(
                        declared.code(),
                        sourceRows.sourceByCode(connection, declared.code())
                                .map(row -> !ruleSets.hasActive(connection, row.id()))
                                .orElse(true));
            }
            fresh = new Cached(clock.instant(), Optional.of(Map.copyOf(missing)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The rule-set coverage could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
