package com.finapp.app.credit;

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
 * The exposure census's verdicts (`P11-TSK-013`; ADR-0091 point 9, PHASE_11_PLAN.md section 15; {@code INV-CRD-09},
 * {@code INV-LND-06}), the {@code LendingCapitalMetrics} shape: {@code finapp.credit.exposure.census{verdict}} - how
 * many parties the census {@code HOLDS} for and how many it {@code FAILS} for; {@code FAILS} must be 0 - alerting - and
 * a CRITICAL log line counts them.
 *
 * <p>Read from ONE {@code REPEATABLE READ} read-only snapshot behind a refresh floor - the cache is this instance's only,
 * never truth; NaN when unreadable, never "clean". Counts only - no figure, party or loan id in a tag or a log line
 * (ADR-0072); which parties fail is the report's ({@link #readNow}), for the investigator.
 */
@Slf4j
public final class ExposureCensusMetrics {

    public static final String CENSUS = "finapp.credit.exposure.census";
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

    private final ExposureCensus census;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public ExposureCensusMetrics(ExposureCensus census, Connections connections, Clock clock, MeterRegistry registry) {
        this.census = Objects.requireNonNull(census, "census");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (Verdict verdict : Verdict.values()) {
            Gauge.builder(CENSUS, this, self -> self.reading().report()
                            .map(report -> (double) (verdict == Verdict.HOLDS ? report.holding() : report.failing()))
                            .orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("Parties the exposure census holds or fails for (INV-CRD-09, INV-LND-06): credit's"
                            + " reserved plus lending's committed and outstanding principal within the limit each"
                            + " deciding policy declared. FAILS must be 0. NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** One census now, bypassing the floor - the suites' and an investigator's view. */
    public ExposureCensus.Report readNow() {
        Reading fresh = read();
        cached.set(fresh);
        return fresh.report().orElseThrow(() -> new IllegalStateException("the exposure census could not be taken"));
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
                ExposureCensus.Report report = census.take(connection);
                if (report.failing() > 0) {
                    log.error("CRITICAL: the exposure census fails for {} part{} (INV-CRD-09, INV-LND-06)",
                            report.failing(), report.failing() == 1 ? "y" : "ies");
                }
                return new Reading(clock.instant(), Optional.of(report));
            } finally {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("Could not take the exposure census; its gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    private record Reading(Instant takenAt, Optional<ExposureCensus.Report> report) {}
}
