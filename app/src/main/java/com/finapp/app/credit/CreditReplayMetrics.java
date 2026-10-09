package com.finapp.app.credit;

import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.DecisionReplayer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The replay proof's verdicts (`P10-TSK-019`; PHASE_10_PLAN.md section 10, {@code INV-CRD-01}):
 * {@code finapp.credit.replay{verdict}} - how many decisions replay {@code IDENTICAL} and how many {@code DIVERGED}
 * from their sealed snapshots and pinned versions; {@code DIVERGED} must be 0 (alerted by `P10-TSK-020`), and a
 * CRITICAL log line names each diverged decision by id. Read from ONE {@code REPEATABLE READ} read-only snapshot behind
 * a refresh floor - the cache is this instance's only, never truth; NaN when unreadable, never "clean". Verdicts only -
 * never a figure.
 */
@Slf4j
public final class CreditReplayMetrics {

    public static final String REPLAY = "finapp.credit.replay";
    static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    private final CreditReplayProof proof;
    private final CreditReadingSnapshot snapshots;
    private final Clock clock;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public CreditReplayMetrics(
            CreditReplayProof proof, CreditReadingSnapshot snapshots, Clock clock, MeterRegistry registry) {
        this.proof = Objects.requireNonNull(proof, "proof");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (DecisionReplayer.Verdict verdict : DecisionReplayer.Verdict.values()) {
            Gauge.builder(REPLAY, this, self -> self.reading().report()
                            .map(report -> (double) report.count(verdict)).orElse(Double.NaN))
                    .tag("verdict", verdict.name())
                    .description("Decisions replaying to this verdict from their sealed snapshots and pinned versions"
                            + " (INV-CRD-01): DIVERGED must be 0. NaN when unreadable. Fleet-wide: max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** One reading now, bypassing the floor - the suites' and an operator's view. */
    public CreditReplayProof.Report readNow() {
        Reading fresh = read();
        cached.set(fresh);
        return fresh.report().orElseThrow(() -> new IllegalStateException("the replay proof could not be read"));
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
            CreditReplayProof.Report report = snapshots.read(proof::prove);
            if (!report.diverged().isEmpty()) {
                log.error("CRITICAL: credit decisions do not replay from their sealed inputs (INV-CRD-01): {}",
                        report.diverged().stream().map(id -> id.value().toString()).toList());
            }
            return new Reading(clock.instant(), Optional.of(report));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the credit replay proof; its gauge reports absent rather than clean: {}",
                    unreadable.getClass().getSimpleName());
            return new Reading(clock.instant(), Optional.empty());
        }
    }

    private record Reading(Instant takenAt, Optional<CreditReplayProof.Report> report) {}
}
