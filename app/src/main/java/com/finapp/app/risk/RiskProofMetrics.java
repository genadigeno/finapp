package com.finapp.app.risk;

import com.finapp.risk.CensusKind;
import com.finapp.risk.ProofVerdict;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskProofs;
import com.finapp.risk.VelocityProof;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The risk proofs, gauged (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.3, 15; {@code DESIGN-023} D10; bnpl's
 * {@code BnplProofMetrics} and `-018`'s {@code RiskCaseBacklogMetrics} shape), registered eagerly for every verdict and
 * census kind, so a fresh instance publishes them from startup:
 *
 * <ul>
 *   <li>{@code finapp.risk.velocity.proof{verdict}} - 1 on the verdict the latest reading of the velocity proof made
 *       whole reached ({@code HELD}, {@code DIVERGED}, {@code VACUOUS}), 0 on the others; {@code DIVERGED} must be 0 -
 *       {@code VelocityDiverged}, `-024`'s rule;</li>
 *   <li>{@code finapp.risk.census{kind,verdict}} - the same for each census ({@link CensusKind}): assessments = judged
 *       commands, monitored = journal, alerts born once, one open case, no filing left undriven - the day-based ones
 *       over today and yesterday (UTC), the worse of the two;</li>
 *   <li>{@code finapp.risk.proof.reading{outcome}} - the readings taken, {@code completed} or {@code failed} (a statement
 *       cancelled by the bound, a lost connection, a refused snapshot): `-024` alerts on a failed rate, never on a gauge
 *       left quietly stale.</li>
 * </ul>
 *
 * <p><strong>No schedule, and never on the scrape thread</strong> (the review's I1). A scrape that finds the reading
 * older than the refresh floor ({@code finapp.risk.proofs.refresh}, 15 minutes by default, never below
 * {@link #MIN_REFRESH}) <em>starts</em> one reading on the refresher and answers the last reading meanwhile: a
 * compare-and-set in-flight flag makes it single-flight per instance - ten scrapes of a slow reading start one. The
 * reading is ONE read-only {@code REPEATABLE READ} snapshot armed with {@code statement_timeout}
 * ({@link RiskProofs#bound}, {@code finapp.risk.proofs.statement-timeout}, 5 s by default) - a fixed set of statements,
 * each bounded. A failed reading leaves the gauges NaN until the next completes ("absent", never "clean"); a reading
 * older than {@link #staleAfter} - two floors, a refresher that never finished - is NaN too, so a stuck instance cannot
 * keep reporting an old {@code HELD}. Nothing here is on a money-moving path: the proofs read, they never gate.
 *
 * <p>A proof writes nothing, so every instance reading it is redundant cost only - no lease, no claim, no system actor.
 * This instance's cache of a derived reading, never truth and never an input to a decision. Fleet-wide {@code max()},
 * never {@code sum()}. A CRITICAL log line names the verdict of what diverged - never a subject, an amount or an
 * identifier ({@code INV-RSK-08}).
 */
@Slf4j
public final class RiskProofMetrics {

    /** Exported as {@code finapp_risk_velocity_proof}. */
    public static final String VELOCITY = "finapp.risk.velocity.proof";

    /** Exported as {@code finapp_risk_census}. */
    public static final String CENSUS = "finapp.risk.census";

    /** Exported as {@code finapp_risk_proof_reading_total}. */
    public static final String READING = "finapp.risk.proof.reading";

    /** A reading that answered. */
    public static final String COMPLETED = "completed";

    /** A reading that threw - the bound's cancellation among the causes. */
    public static final String FAILED = "failed";

    /** The shortest floor a configuration may set: a reading takes every census and the whole velocity proof. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    /** The production floor - four readings an hour per scraped instance (bnpl's). */
    public static final Duration DEFAULT_REFRESH = Duration.ofMinutes(15);

    private final Supplier<RiskOperationsReports.Reading> readings;
    private final Executor refresher;
    private final Clock clock;
    private final Duration refresh;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final Counter completed;
    private final Counter failed;

    private record Cached(Instant takenAt, Optional<RiskOperationsReports.Reading> reading) {}

    /**
     * {@code proofs}' reading on one snapshot of {@code transactions}, every statement bounded by
     * {@code statementTimeout} - the composition's.
     */
    public static Supplier<RiskOperationsReports.Reading> over(RiskOperationsReports proofs,
            RiskTransactions transactions, Duration statementTimeout) {
        Objects.requireNonNull(proofs, "proofs");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        return () -> transactions.inSnapshot(snapshot -> {
            RiskProofs.bound(snapshot, statementTimeout);
            return proofs.reading(snapshot);
        });
    }

    /** A refresher that runs each reading on a virtual thread of its own - the composition's. */
    public static Executor virtualThreads() {
        return reading -> Thread.ofVirtual().name("risk-proof-reading").start(reading);
    }

    /**
     * @param readings one bounded reading of the proofs on one read-only {@code REPEATABLE READ} snapshot ({@link #over})
     * @param refresher where a reading runs - never the scrape's thread in production ({@link #virtualThreads})
     * @param refresh the refresh floor - at least {@link #MIN_REFRESH}
     */
    public RiskProofMetrics(Supplier<RiskOperationsReports.Reading> readings, Executor refresher, Clock clock,
            MeterRegistry registry, Duration refresh) {
        this.readings = Objects.requireNonNull(readings, "readings");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        if (refresh.compareTo(MIN_REFRESH) < 0) {
            throw new IllegalArgumentException("the risk proofs' refresh floor is at least " + MIN_REFRESH);
        }
        Objects.requireNonNull(registry, "registry");
        for (VelocityProof.Verdict verdict : VelocityProof.Verdict.values()) {
            Gauge.builder(VELOCITY, this, self -> self.figure(reading -> reading.velocity() == verdict))
                    .tag("verdict", verdict.name())
                    .description("1 on the verdict the latest reading of the velocity proof made whole reached - every"
                            + " live window against the counted assessments and, for windows of transfers alone, the"
                            + " monitored journal (INV-RSK-03); 0 on the others. DIVERGED must be 0 (VelocityDiverged)."
                            + " NaN when unreadable or stale. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (CensusKind kind : CensusKind.values()) {
            for (ProofVerdict verdict : ProofVerdict.values()) {
                Gauge.builder(CENSUS, this, self -> self.figure(reading -> reading.censuses().get(kind) == verdict))
                        .tag("kind", kind.name())
                        .tag("verdict", verdict.name())
                        .description("1 on the verdict the latest reading of the census reached (today and yesterday,"
                                + " UTC, for the day-based ones); 0 on the others. DIVERGED must be 0. NaN when"
                                + " unreadable or stale. Fleet-wide: max(), never sum()")
                        .strongReference(true)
                        .register(registry);
            }
        }
        this.completed = Counter.builder(READING).tag("outcome", COMPLETED)
                .description("Readings of the risk proofs that answered, off the scrape thread, one at a time per"
                        + " instance").register(registry);
        this.failed = Counter.builder(READING).tag("outcome", FAILED)
                .description("Readings of the risk proofs that threw - a statement past the bound, a lost connection;"
                        + " the gauges read NaN until one completes").register(registry);
    }

    /** One reading now, on the caller's thread, bypassing the floor - the suites' and an operator's view. */
    public RiskOperationsReports.Reading readNow() {
        Cached fresh = read();
        cached.set(fresh);
        return fresh.reading().orElseThrow(() -> new IllegalStateException("the risk proofs could not be read"));
    }

    /** The floor this instance reads behind. */
    public Duration refresh() {
        return refresh;
    }

    /** Past this age a reading is served NaN: two floors - the refresher did not finish one in time. */
    public Duration staleAfter() {
        return refresh.multipliedBy(2);
    }

    private double figure(java.util.function.Predicate<RiskOperationsReports.Reading> holds) {
        Cached current = refreshed();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(staleAfter()) >= 0) {
            return Double.NaN;
        }
        return current.reading().map(reading -> holds.test(reading) ? 1.0 : 0.0).orElse(Double.NaN);
    }

    /** The last reading; when it is past the floor, one more started on the refresher unless one already runs. */
    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(refresh) < 0
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
        } catch (RejectedExecutionException refused) {
            inFlight.set(false);
            failed.increment();
            log.warn("Could not start a reading of the risk proofs; their gauges keep the last: {}",
                    refused.getClass().getSimpleName());
        }
        return cached.get();
    }

    private Cached read() {
        try {
            RiskOperationsReports.Reading reading = readings.get();
            completed.increment();
            if (reading.velocity() == VelocityProof.Verdict.DIVERGED) {
                log.error("CRITICAL: the velocity proof diverged (INV-RSK-03): {}", reading.velocity().name());
            }
            reading.censuses().forEach((kind, verdict) -> {
                if (verdict == ProofVerdict.DIVERGED) {
                    log.error("CRITICAL: a risk census diverged: {} {}", kind.name(), verdict.name());
                }
            });
            return new Cached(clock.instant(), Optional.of(reading));
        } catch (RuntimeException unreadable) {
            failed.increment();
            log.warn("Could not read the risk proofs; their gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Cached(clock.instant(), Optional.empty());
        }
    }
}
