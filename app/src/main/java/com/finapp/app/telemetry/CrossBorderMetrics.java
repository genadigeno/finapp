package com.finapp.app.telemetry;

import com.finapp.ledger.SupportedCurrencies;
import com.finapp.payments.CorridorDeclaration;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The cross-border payment's meters (`P9-TSK-027`, PHASE_9_PLAN.md section 15): counts, latencies and the oldest
 * in-transit age per corridor - never an amount (ADR-0072, D32). Every series is registered eagerly for every corridor
 * the build can carry: each declared corridor rail's coverage (destination country, destination currency) crossed with
 * the supported source currencies, a compiled superset of any corridor policy - so a fresh instance publishes every
 * row, and the {@code corridor} tag is bounded by the code. A count for a corridor outside that set is dropped, never
 * a new series.
 *
 * <p>Counters count committed facts after commit ({@link AfterCommit}). The in-transit age is a gauge read from the
 * shared database - every instance publishes the same reading, so it aggregates with {@code max()} - and reads NaN
 * when unreadable, never zero; a corridor with nothing in transit reads 0.
 */
public final class CrossBorderMetrics {

    public static final String PAYMENT = "finapp.crossborder.payment";
    public static final String LATENCY = "finapp.crossborder.payment.latency";
    public static final String IN_TRANSIT_AGE = "finapp.crossborder.payment.in.transit.age";
    public static final String RETURN = "finapp.crossborder.return";
    public static final String CANCELLATION = "finapp.crossborder.cancellation";

    static final List<String> PAYMENT_OUTCOMES =
            List.of("submitted", "in_transit", "delivered", "failed", "cancelled", "returned");
    static final List<String> STAGES = List.of("accept", "deliver");
    static final List<String> RETURN_OUTCOMES =
            List.of("applied", "deferred", "not_applicable", "already_returned", "resolved");

    /** How long one database reading of the in-transit ages serves every corridor's gauge. */
    private static final Duration READING_TTL = Duration.ofSeconds(5);

    private final Set<String> corridors;
    private final Map<String, Counter> payments = new ConcurrentHashMap<>();
    private final Map<String, Timer> latencies = new ConcurrentHashMap<>();
    private final Map<String, Counter> returns = new ConcurrentHashMap<>();
    private final Map<String, Counter> cancellations = new ConcurrentHashMap<>();
    private final Function<Connection, Map<String, Instant>> inTransitSince;
    private final ConnectionSource connections;
    private final Clock clock;
    private volatile Reading reading;

    /** A connection, for the gauge's own read - never a request's transaction. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    private record Reading(Instant at, Map<String, Instant> since, boolean readable) {}

    public CrossBorderMetrics(
            Collection<CorridorDeclaration> declarations,
            Function<Connection, Map<String, Instant>> inTransitSince,
            ConnectionSource connections,
            Clock clock,
            MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        this.inTransitSince = Objects.requireNonNull(inTransitSince, "inTransitSince must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.corridors = corridors(declarations);
        for (String outcome : List.of("recalled", "too_late")) {
            cancellations.put(outcome, Counter.builder(CANCELLATION).tag("outcome", outcome)
                    .description("Cancellation recalls answered: recalled, or too late - the free-option watch. A count,"
                            + " never an amount")
                    .register(registry));
        }
        for (String corridor : corridors) {
            for (String outcome : PAYMENT_OUTCOMES) {
                payments.put(corridor + "|" + outcome, Counter.builder(PAYMENT).tag("corridor", corridor)
                        .tag("outcome", outcome)
                        .description("Cross-border payments per corridor by edge. A count, never an amount")
                        .register(registry));
            }
            for (String stage : STAGES) {
                latencies.put(corridor + "|" + stage, Timer.builder(LATENCY).tag("corridor", corridor).tag("stage", stage)
                        .description("Authorization to the provider's acceptance (accept) and to delivery (deliver)")
                        .register(registry));
            }
            for (String outcome : RETURN_OUTCOMES) {
                returns.put(corridor + "|" + outcome, Counter.builder(RETURN).tag("corridor", corridor)
                        .tag("outcome", outcome)
                        .description("Cross-border returns: applied, deferred, not applicable, already returned or"
                                + " resolved by a person. A count, never an amount")
                        .register(registry));
            }
            Gauge.builder(IN_TRANSIT_AGE, this, metrics -> metrics.inTransitAge(corridor))
                    .tag("corridor", corridor)
                    .description("Seconds the OLDEST payment in transit on this corridor has waited for its delivery since"
                            + " the provider accepted it. NaN when unreadable, never zero. Fleet-wide: aggregate with"
                            + " max(), never sum()")
                    .register(registry);
        }
    }

    /** Every corridor the build can carry: each declared coverage crossed with the supported source currencies. */
    static Set<String> corridors(Collection<CorridorDeclaration> declarations) {
        Set<String> corridors = new TreeSet<>();
        for (CorridorDeclaration declaration : declarations) {
            for (CorridorDeclaration.Coverage coverage : declaration.coverage()) {
                for (CurrencyCode source : SupportedCurrencies.ALL) {
                    if (!source.equals(coverage.currency())) {
                        corridors.add(source.code() + "-" + coverage.currency().code() + "-" + coverage.country().code());
                    }
                }
            }
        }
        return Set.copyOf(corridors);
    }

    /** One payment edge on {@code corridor}, counted once its transaction commits. */
    public void payment(String corridor, String outcome) {
        Counter counter = payments.get(corridor + "|" + outcome);
        if (counter != null) {
            AfterCommit.run(counter::increment);
        }
    }

    /** One stage's latency on {@code corridor}, recorded once its transaction commits. */
    public void latency(String corridor, String stage, Duration elapsed) {
        Timer timer = latencies.get(corridor + "|" + stage);
        if (timer != null && !elapsed.isNegative()) {
            AfterCommit.run(() -> timer.record(elapsed));
        }
    }

    /** A cancellation's recall answered - {@code recalled} or {@code too_late} - counted once its transaction commits. */
    public void cancellation(String outcome) {
        Counter counter = cancellations.get(outcome);
        if (counter != null) {
            AfterCommit.run(counter::increment);
        }
    }

    /** One return outcome on {@code corridor}, counted once its transaction commits. */
    public void returned(String corridor, String outcome) {
        Counter counter = returns.get(corridor + "|" + outcome);
        if (counter != null) {
            AfterCommit.run(counter::increment);
        }
    }

    private double inTransitAge(String corridor) {
        Reading current = current();
        if (!current.readable()) {
            return Double.NaN;
        }
        Instant since = current.since().get(corridor);
        return since == null ? 0 : Math.max(0, Duration.between(since, current.at()).toSeconds());
    }

    private Reading current() {
        Reading cached = reading;
        Instant now = clock.instant();
        if (cached != null && Duration.between(cached.at(), now).compareTo(READING_TTL) < 0) {
            return cached;
        }
        Reading fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Reading(now, Map.copyOf(inTransitSince.apply(connection)), true);
        } catch (SQLException | RuntimeException unreadable) {
            fresh = new Reading(now, Map.of(), false);
        }
        reading = fresh;
        return fresh;
    }
}
