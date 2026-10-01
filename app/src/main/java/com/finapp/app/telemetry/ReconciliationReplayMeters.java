package com.finapp.app.telemetry;

import com.finapp.reconciliation.ReplayObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * {@code finapp.reconciliation.replay} (`P8-TSK-022`, `PHASE_8_PLAN.md` §15; ADR-0072: a verdict,
 * never an amount): one count per appended decision replay, by {@code outcome} -
 * {@code identical} or {@code diverged}. Eager in both outcomes, so the determinism alert has a
 * series from the first scrape and a diverged count is never absent-for-zero.
 */
public final class ReconciliationReplayMeters implements ReplayObserver {

    /** {@code finapp.reconciliation.replay} - replays appended, by verdict. */
    public static final String REPLAY = "finapp.reconciliation.replay";

    private static final List<String> OUTCOMES = List.of("identical", "diverged");

    private final MeterRegistry registry;

    public ReconciliationReplayMeters(MeterRegistry registry) {
        this.registry = registry;
        OUTCOMES.forEach(this::counter);
    }

    @Override
    public void replayed(String outcome) {
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("a replay outcome is identical or diverged");
        }
        counter(outcome).increment();
    }

    private Counter counter(String outcome) {
        return Counter.builder(REPLAY)
                .tag("outcome", outcome)
                .description(
                        "Decision replays appended, by verdict: a diverged replay means a stored"
                                + " decision no longer follows from its own snapshot - the"
                                + " determinism alert. A count, never an amount")
                .register(registry);
    }
}
