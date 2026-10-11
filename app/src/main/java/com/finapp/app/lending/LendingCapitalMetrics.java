package com.finapp.app.lending;

import com.finapp.lending.LendingCapitalProof;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The capital proof's verdicts (`P11-TSK-004`; ADR-0096 section 6, PHASE_11_PLAN.md section 15; {@code INV-LND-13}),
 * the {@code FxProofMetrics} shape:
 *
 * <ul>
 *   <li>{@code finapp.lending.capital.proof{verdict}} - how many supported currencies the capital proof
 *       {@code HOLDS} for and how many it {@code FAILS} for (a capital line no recognition explains, or headroom below
 *       zero at rest); {@code FAILS} must be 0 - alerting - and a CRITICAL log line names each failing currency.
 *   <li>{@code finapp.lending.capital.headroom.low} - how many currencies' headroom stands below the configured floor
 *       ({@code finapp.lending.capital.headroom-floor.<CCY>}, minor units, 0 when unset); alerting above 0.
 * </ul>
 *
 * <p>Read from ONE {@code REPEATABLE READ} read-only snapshot behind a refresh floor - the cache is this instance's
 * only, never truth; NaN when unreadable, never "clean". Counts and verdicts only - the headroom amounts are an audited
 * report ({@code GET /v1/operator/lending/capital}), never a metric (ADR-0072).
 */
@Slf4j
public final class LendingCapitalMetrics {

    public static final String PROOF = "finapp.lending.capital.proof";
    public static final String HEADROOM_LOW = "finapp.lending.capital.headroom.low";
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    /** The closed verdict tag. */
    public enum Verdict {
        HOLDS,
        FAILS
    }

    /** Opens a connection for one reading. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final LendingCapitalProof proof;
    private final Connections connections;
    private final Map<String, Long> floors;
    private final Clock clock;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public LendingCapitalMetrics(
            LendingCapitalProof proof,
            Connections connections,
            Map<String, Long> floors,
            Clock clock,
            MeterRegistry registry) {
        this.proof = Objects.requireNonNull(proof, "proof");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.floors = Map.copyOf(Objects.requireNonNull(floors, "floors"));
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (Verdict verdict : Verdict.values()) {
            Gauge.builder(PROOF, this, self -> self.reading().report()
                            .map(report -> (double) (verdict == Verdict.HOLDS ? report.holding() : report.failing()))
                            .orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("Supported currencies the lending capital proof holds or fails for (INV-LND-13):"
                            + " every capital line a recognised contribution's, headroom >= 0 at rest. FAILS must be 0."
                            + " NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(HEADROOM_LOW, this, self -> self.reading().report()
                        .map(report -> (double) report.belowFloor(self.floors))
                        .orElse(Double.NaN))
                .description("Currencies whose lending capital headroom stands below the configured floor - alerting"
                        + " above 0. Counts only: the amounts are an audited report (ADR-0072). NaN when unreadable")
                .strongReference(true)
                .register(registry);
    }

    /** One reading now, bypassing the floor - the suites' and an operator's view. */
    public LendingCapitalProof.Report readNow() {
        Reading fresh = read();
        cached.set(fresh);
        return fresh.report().orElseThrow(() -> new IllegalStateException("the capital proof could not be read"));
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.takenAt().plus(MIN_REFRESH).isBefore(clock.instant())) {
            return current;
        }
        Reading fresh = read();
        cached.set(fresh);
        return fresh;
    }

    private Reading read() {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try {
                LendingCapitalProof.Report report = proof.prove(connection);
                List<String> failing = report.currencies().stream()
                        .filter(verdict -> !verdict.holds())
                        .map(LendingCapitalProof.CurrencyVerdict::toString)
                        .toList();
                if (!failing.isEmpty()) {
                    log.error("CRITICAL: the lending capital proof fails (INV-LND-13): {}", failing);
                }
                return new Reading(clock.instant(), Optional.of(report));
            } finally {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("Could not read the lending capital proof; its gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    private record Reading(Instant takenAt, Optional<LendingCapitalProof.Report> report) {}
}
