package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * BNPL's three proofs, gauged (`P12-TSK-025`; {@code PHASE_12_PLAN.md} sections 12.12 and 15; lending's
 * {@code LendingProofMetrics} shape):
 *
 * <ul>
 *   <li>{@code finapp.bnpl.threeway.proof{verdict}} - how many currencies the three-way proof {@code HOLDS} in and how many
 *       it {@code FAILS} in (merchant leg, customer leg, economic identity, ledger - the capital proof and the exposure
 *       census read in the same snapshot); {@code FAILS} must be 0 - alerting;
 *   <li>{@code finapp.bnpl.subledger.proof{verdict}} - how many obligations' subledger proof {@code HOLDS} and how many
 *       {@code FAILS};
 *   <li>{@code finapp.bnpl.replay{verdict}} - how many obligations replay {@code IDENTICAL} and how many {@code DIVERGED};
 *       {@code DIVERGED} must be 0 - alerting.
 * </ul>
 *
 * <p>All three from ONE reading: every obligation's record read once on one read-only {@code REPEATABLE READ} snapshot
 * behind a refresh floor - this instance's cache of a derived reading, never truth and never an input to a decision; NaN
 * when unreadable, never "clean". Each instance computes the same counts from the same rows; fleet-wide: {@code max()},
 * never {@code sum()}. Counts only - the figures are the explanation's (an audited read), never a metric (ADR-0072); a
 * CRITICAL log line counts the failing arms and kinds, never an obligation, a party or an amount. The floor
 * ({@code finapp.bnpl.proofs.refresh}, default 15 minutes, never below {@link #MIN_REFRESH}) bounds a reading's cost - one
 * statement per bnpl table per obligation - to four an hour per scraped instance.
 */
@Slf4j
public final class BnplProofMetrics {

    public static final String THREE_WAY = "finapp.bnpl.threeway.proof";
    public static final String SUBLEDGER = "finapp.bnpl.subledger.proof";
    public static final String REPLAY = "finapp.bnpl.replay";

    /** The shortest floor a configuration may set: a reading reads every obligation's whole record. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    /** The production floor: four readings an hour per scraped instance (lending's). */
    public static final Duration DEFAULT_REFRESH = Duration.ofMinutes(15);

    /** The two-valued verdict of the three-way and the subledger proofs. */
    public enum Holds {
        HOLDS,
        FAILS
    }

    private final BnplProofs proofs;
    private final Function<Function<Connection, BnplProofs.Reading>, BnplProofs.Reading> snapshots;
    private final Clock clock;
    private final Duration refresh;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    /** At the {@link #MIN_REFRESH} floor - the suites'. */
    public BnplProofMetrics(BnplProofs proofs,
            Function<Function<Connection, BnplProofs.Reading>, BnplProofs.Reading> snapshots, Clock clock,
            MeterRegistry registry) {
        this(proofs, snapshots, clock, registry, MIN_REFRESH);
    }

    /**
     * @param snapshots runs a reading on one read-only {@code REPEATABLE READ} snapshot ({@link BnplTransactions#inSnapshot})
     * @param refresh the refresh floor - at least {@link #MIN_REFRESH}
     */
    public BnplProofMetrics(BnplProofs proofs,
            Function<Function<Connection, BnplProofs.Reading>, BnplProofs.Reading> snapshots, Clock clock,
            MeterRegistry registry, Duration refresh) {
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        if (refresh.compareTo(MIN_REFRESH) < 0) {
            throw new IllegalArgumentException("the BNPL proofs' refresh floor is at least " + MIN_REFRESH);
        }
        Objects.requireNonNull(registry, "registry");
        for (Holds verdict : Holds.values()) {
            Gauge.builder(THREE_WAY, this, self -> self.reading().reading()
                            .map(found -> (double) found.currencies(verdict == Holds.HOLDS)).orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("Currencies in which every BNPL purchase is proven three ways - merchant leg, customer"
                            + " leg, economic identity, trial balance, capital and exposure (gate criterion 5). FAILS"
                            + " must be 0. NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(SUBLEDGER, this, self -> self.reading().reading()
                            .map(found -> (double) (verdict == Holds.HOLDS ? found.holding() : found.failing()))
                            .orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("BNPL obligations whose six ledger balances their rows explain exactly (INV-BNPL-01):"
                            + " every entry named, no balance negative, a closed obligation zero. FAILS must be 0. NaN"
                            + " when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (BnplReplayProof.Verdict verdict : BnplReplayProof.Verdict.values()) {
            Gauge.builder(REPLAY, this, self -> self.reading().reading()
                            .map(found -> (double) found.replaying(verdict)).orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("BNPL obligations whose every figure re-derives by its pinned engine and whose canonical"
                            + " terms re-verify: DIVERGED must be 0. NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** One reading now, bypassing the floor - the suites' and an operator's view. */
    public BnplProofs.Reading readNow() {
        Reading fresh = read();
        cached.set(fresh);
        return fresh.reading().orElseThrow(() -> new IllegalStateException("the BNPL proofs could not be read"));
    }

    /** The floor this instance reads behind. */
    public Duration refresh() {
        return refresh;
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.takenAt().plus(refresh).isBefore(clock.instant())) {
            return current;
        }
        Reading fresh = read();
        cached.set(fresh);
        return fresh;
    }

    private Reading read() {
        try {
            BnplProofs.Reading reading = snapshots.apply(proofs::prove);
            if (reading.currencies(false) > 0) {
                log.error("CRITICAL: {} currencies' BNPL purchases are not proven three ways (gate criterion 5);"
                        + " failing currencies per arm: {}", reading.currencies(false), arms(reading));
            }
            if (reading.failing() > 0) {
                log.error("CRITICAL: {} BNPL obligations' ledger balances are not explained by their rows"
                        + " (INV-BNPL-01); failing obligations per arm: {}", reading.failing(), subledgerArms(reading));
            }
            if (!reading.divergences().isEmpty()) {
                log.error("CRITICAL: {} BNPL obligations do not replay from their pinned engines and terms;"
                        + " diverged obligations per kind: {}", reading.divergences().size(), kinds(reading));
            }
            return new Reading(clock.instant(), Optional.of(reading));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the BNPL proofs; their gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    /** Failing currencies per arm - arm names and counts, never a currency's figure. */
    static Map<String, Long> arms(BnplProofs.Reading reading) {
        Map<String, Long> arms = new TreeMap<>();
        for (BnplThreeWayProof.CurrencyVerdict currency : reading.currencies()) {
            currency.failures().forEach(arm -> arms.merge(arm, 1L, Long::sum));
        }
        return arms;
    }

    /** Failing obligations per subledger arm - names and counts only. */
    static Map<String, Long> subledgerArms(BnplProofs.Reading reading) {
        Map<String, Long> arms = new TreeMap<>();
        for (BnplSubledgerProof.Verdict verdict : reading.failures()) {
            verdict.failures().forEach(arm -> arms.merge(arm, 1L, Long::sum));
        }
        return arms;
    }

    /** Diverged obligations per kind - kind names and counts only. */
    static Map<String, Long> kinds(BnplProofs.Reading reading) {
        Map<String, Long> kinds = new TreeMap<>();
        for (BnplReplayProof.Replay replay : reading.divergences()) {
            replay.checks().forEach(check -> kinds.merge(check.name(), 1L, Long::sum));
        }
        return kinds;
    }

    private record Reading(Instant takenAt, Optional<BnplProofs.Reading> reading) {}
}
