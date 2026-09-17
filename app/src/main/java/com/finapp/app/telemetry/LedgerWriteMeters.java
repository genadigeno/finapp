package com.finapp.app.telemetry;

import com.finapp.ledger.PostingObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The write path's meters (`P3-TSK-020`, `PHASE_3_PLAN.md` §15): {@code finapp.ledger.posting}
 * by outcome, and {@code finapp.ledger.posting.latency}.
 *
 * <p><strong>Every series is registered at construction</strong> (`P1-TSK-029`): a counter
 * created on its first increment is a series an alert cannot evaluate at exactly the moment
 * it is needed, and a freshly started instance must publish a healthy zero rather than an
 * absence that reads as a quiet system. One series per {@link PostingObserver.Outcome} value,
 * derived from the enum so a new outcome registers itself.
 *
 * <p>The latency timer records <strong>every</strong> command invocation, whatever its
 * outcome — it is the latency a caller experiences on the hot path of every later phase, and
 * a timer that recorded only successes would flatter exactly the incident an operator is
 * trying to see. No percentile histogram is published (the `P1-TSK-029` lesson:
 * {@code _bucket} does not exist unless asked for, and a dashboard querying it renders "No
 * data"); the dashboard reads {@code _count}, {@code _sum} and {@code _max}.
 */
final class LedgerWriteMeters implements PostingObserver {

    /** {@code finapp.ledger.posting} — journal-write commands, by outcome. */
    static final String POSTING = "finapp.ledger.posting";

    /** {@code finapp.ledger.posting.latency} — the command's duration, every outcome. */
    static final String POSTING_LATENCY = "finapp.ledger.posting.latency";

    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private final Timer latency;

    LedgerWriteMeters(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(
                    outcome,
                    Counter.builder(POSTING)
                            .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                            .description(
                                    "Journal-write commands through the ledger's one write"
                                        + " path (posting, reversal, adjustment): posted ran"
                                        + " and appended, replayed returned a recorded"
                                        + " outcome, refused threw. Per instance; rate() and"
                                        + " sum() aggregate")
                            .register(registry));
        }
        this.latency =
                Timer.builder(POSTING_LATENCY)
                        .description(
                                "Duration of a journal-write command, every outcome - the"
                                    + " hot path of every later phase. No histogram buckets:"
                                    + " read _count, _sum and _max")
                        .register(registry);
    }

    @Override
    public void observe(Outcome outcome, Duration elapsed) {
        outcomes.get(outcome).increment();
        latency.record(elapsed);
    }
}
