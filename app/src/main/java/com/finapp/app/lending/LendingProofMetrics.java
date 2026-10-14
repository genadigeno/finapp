package com.finapp.app.lending;

import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanReplayProof;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Lending's two proofs, gauged (`P11-TSK-029`; PHASE_11_PLAN.md section 15; {@code INV-LND-01}, {@code INV-LND-05},
 * {@code INV-LND-09}) - the {@code LendingCapitalMetrics} shape:
 *
 * <ul>
 *   <li>{@code finapp.lending.subledger.proof{verdict}} - how many loan accounts' subledger proof {@code HOLDS} and how
 *       many {@code FAILS}; {@code FAILS} must be 0 - alerting - and a CRITICAL log line names each failing account and
 *       the arms that fail (identifiers and arm names, never an amount).
 *   <li>{@code finapp.lending.replay{verdict}} - how many accounts replay {@code IDENTICAL} and how many
 *       {@code DIVERGED}; {@code DIVERGED} must be 0 - alerting - each diverged account and kind logged CRITICAL.
 * </ul>
 *
 * <p>Both from ONE reading: every account's record read once on one read-only {@code REPEATABLE READ} snapshot behind a
 * refresh floor ({@link #MIN_REFRESH}) - this instance's cache only, never truth; NaN when unreadable, never "clean". Each
 * instance computes the same counts from the same rows; fleet-wide: {@code max()}, never {@code sum()}. Counts only - the
 * figures are the explanation's (an audited read), never a metric (ADR-0072).
 */
@Slf4j
public final class LendingProofMetrics {

    public static final String SUBLEDGER = "finapp.lending.subledger.proof";
    public static final String REPLAY = "finapp.lending.replay";

    /** The reading reads every account's whole record: at most once a minute per instance. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    /** The subledger verdict tag. */
    public enum SubledgerVerdict {
        HOLDS,
        FAILS
    }

    private final LendingProofs proofs;
    private final Function<Function<Connection, LendingProofs.Reading>, LendingProofs.Reading> snapshots;
    private final Clock clock;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    /**
     * @param snapshots runs a reading on one read-only {@code REPEATABLE READ} snapshot ({@link LendingTransactions#inSnapshot})
     */
    public LendingProofMetrics(LendingProofs proofs,
            Function<Function<Connection, LendingProofs.Reading>, LendingProofs.Reading> snapshots, Clock clock,
            MeterRegistry registry) {
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        try {
            LendingProofs.Reading reading = snapshots.apply(proofs::prove);
            if (reading.failing() > 0) {
                log.error("CRITICAL: loan accounts' ledger balances are not explained by their rows (INV-LND-01): {}",
                        reading.failures());
            }
            if (!reading.divergences().isEmpty()) {
                log.error("CRITICAL: loan accounts do not replay from their pinned engines and terms (INV-LND-05,"
                        + " INV-LND-09): {}", reading.divergences().stream()
                        .map(replay -> replay.loan().value() + "=" + replay.checks()).toList());
            }
            return new Reading(clock.instant(), Optional.of(reading));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the lending proofs; their gauges report absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    private record Reading(Instant takenAt, Optional<LendingProofs.Reading> reading) {}
}
