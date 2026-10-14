package com.finapp.app.lending;

import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanSubledgerProof;
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
 * Lending's two proofs, gauged (`P11-TSK-029`; PHASE_11_PLAN.md section 15; {@code INV-LND-01}, {@code INV-LND-05},
 * {@code INV-LND-09}) - the {@code LendingCapitalMetrics} shape:
 *
 * <ul>
 *   <li>{@code finapp.lending.subledger.proof{verdict}} - how many loan accounts' subledger proof {@code HOLDS} and how
 *       many {@code FAILS}; {@code FAILS} must be 0 - alerting ({@code LendingSubledgerProofFailing}) - and a CRITICAL log
 *       line counts the failing accounts per arm that fails (arm names and counts, never an amount or a loan id).
 *   <li>{@code finapp.lending.replay{verdict}} - how many accounts replay {@code IDENTICAL} and how many
 *       {@code DIVERGED}; {@code DIVERGED} must be 0 - alerting ({@code LendingReplayDiverged}) - the CRITICAL line counting
 *       the diverged accounts per kind.
 * </ul>
 *
 * <p>Both from ONE reading: every account's record read once on one read-only {@code REPEATABLE READ} snapshot behind a
 * refresh floor - this instance's cache only, never truth; NaN when unreadable, never "clean". Each instance computes the
 * same counts from the same rows; fleet-wide: {@code max()}, never {@code sum()}. Counts only - the figures are the
 * explanation's (an audited read), never a metric (ADR-0072); WHICH accounts fail or diverge is the portfolio report's
 * (an audited read) - a log line names no loan (PHASE_11_PLAN.md section 11; `P11-TSK-030`, which removed the ids
 * `P11-TSK-029`'s line carried).
 *
 * <p><strong>The floor is the reading's cost control</strong> (`P11-TSK-030`, the carry from `-029`): one reading reads
 * every account's whole record - a statement per lending table and the journal lines, per account - so a reading costs
 * O(accounts x tables) statements on every instance that is scraped. The floor ({@code finapp.lending.proofs.refresh},
 * default 15 minutes, never below {@link #MIN_REFRESH}) bounds it to four readings an hour per instance, and the gauges
 * are only read when scraped. A partitioned or incremental proof is the debt row's (`CURRENT_STATE.md`), due before the
 * book reaches the size at which one reading outlasts the floor.
 */
@Slf4j
public final class LendingProofMetrics {

    public static final String SUBLEDGER = "finapp.lending.subledger.proof";
    public static final String REPLAY = "finapp.lending.replay";

    /** The shortest floor a configuration may set: a reading reads every account's whole record. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    /** The production floor (`P11-TSK-030`): four readings an hour per scraped instance. */
    public static final Duration DEFAULT_REFRESH = Duration.ofMinutes(15);

    /** The subledger verdict tag. */
    public enum SubledgerVerdict {
        HOLDS,
        FAILS
    }

    private final LendingProofs proofs;
    private final Function<Function<Connection, LendingProofs.Reading>, LendingProofs.Reading> snapshots;
    private final Clock clock;
    private final Duration refresh;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    /** At the {@link #MIN_REFRESH} floor - the suites'. */
    public LendingProofMetrics(LendingProofs proofs,
            Function<Function<Connection, LendingProofs.Reading>, LendingProofs.Reading> snapshots, Clock clock,
            MeterRegistry registry) {
        this(proofs, snapshots, clock, registry, MIN_REFRESH);
    }

    /**
     * @param snapshots runs a reading on one read-only {@code REPEATABLE READ} snapshot ({@link LendingTransactions#inSnapshot})
     * @param refresh the refresh floor - at least {@link #MIN_REFRESH}
     */
    public LendingProofMetrics(LendingProofs proofs,
            Function<Function<Connection, LendingProofs.Reading>, LendingProofs.Reading> snapshots, Clock clock,
            MeterRegistry registry, Duration refresh) {
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        if (refresh.compareTo(MIN_REFRESH) < 0) {
            throw new IllegalArgumentException("the lending proofs' refresh floor is at least " + MIN_REFRESH);
        }
        Objects.requireNonNull(registry, "registry");
        for (SubledgerVerdict verdict : SubledgerVerdict.values()) {
            Gauge.builder(SUBLEDGER, this, self -> self.reading().reading()
                            .map(found -> (double) (verdict == SubledgerVerdict.HOLDS ? found.holding() : found.failing()))
                            .orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("Loan accounts whose six ledger balances their rows explain exactly (INV-LND-01): every"
                            + " entry named, no balance negative, a closed account zero. FAILS must be 0. NaN when"
                            + " unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (LoanReplayProof.Verdict verdict : LoanReplayProof.Verdict.values()) {
            Gauge.builder(REPLAY, this, self -> self.reading().reading()
                            .map(found -> (double) found.replaying(verdict))
                            .orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("Loan accounts whose every figure re-derives by its pinned engine and whose canonical terms"
                            + " re-verify (INV-LND-05, INV-LND-09): DIVERGED must be 0. NaN when unreadable. Fleet-wide:"
                            + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** One reading now, bypassing the floor - the suites' and an operator's view. */
    public LendingProofs.Reading readNow() {
        Reading fresh = read();
        cached.set(fresh);
        return fresh.reading().orElseThrow(() -> new IllegalStateException("the lending proofs could not be read"));
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
            LendingProofs.Reading reading = snapshots.apply(proofs::prove);
            if (reading.failing() > 0) {
                log.error("CRITICAL: {} loan accounts' ledger balances are not explained by their rows (INV-LND-01);"
                        + " failing accounts per arm: {}", reading.failing(), arms(reading));
            }
            if (!reading.divergences().isEmpty()) {
                log.error("CRITICAL: {} loan accounts do not replay from their pinned engines and terms (INV-LND-05,"
                        + " INV-LND-09); diverged accounts per kind: {}", reading.divergences().size(), kinds(reading));
            }
            return new Reading(clock.instant(), Optional.of(reading));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the lending proofs; their gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    /** Failing accounts per failing arm - arm names and counts, never a loan or an amount. */
    static Map<String, Long> arms(LendingProofs.Reading reading) {
        Map<String, Long> arms = new TreeMap<>();
        for (LoanSubledgerProof.Verdict verdict : reading.failures()) {
            verdict.failures().forEach(arm -> arms.merge(arm, 1L, Long::sum));
        }
        return arms;
    }

    /** Diverged accounts per kind - kind names and counts, never a loan or a figure. */
    static Map<String, Long> kinds(LendingProofs.Reading reading) {
        Map<String, Long> kinds = new TreeMap<>();
        for (LoanReplayProof.Replay replay : reading.divergences()) {
            replay.checks().forEach(check -> kinds.merge(check.name(), 1L, Long::sum));
        }
        return kinds;
    }

    private record Reading(Instant takenAt, Optional<LendingProofs.Reading> reading) {}
}
