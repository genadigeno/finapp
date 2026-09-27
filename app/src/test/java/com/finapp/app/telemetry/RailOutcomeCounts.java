package com.finapp.app.telemetry;

import com.finapp.payments.RailId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reads the rail outcome series off a registry (`P7-TSK-015`) — for the database suites that drive
 * real flows through the WIRED appliers and assert the judgement they committed was counted once,
 * after its commit. Counters are cumulative and the suites share one context, so callers compare a
 * reading before the act with one after it — a delta, never an absolute.
 */
public final class RailOutcomeCounts {

    private RailOutcomeCounts() {}

    /** {@code finapp.payments.rail.outcome{rail,type,outcome}} — zero when never registered. */
    public static double railOutcome(
            MeterRegistry registry, RailId rail, String type, String outcome) {
        Counter counter =
                registry.find(PaymentMeters.RAIL_OUTCOME)
                        .tag("rail", rail.value())
                        .tag("type", type)
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    /** The legacy {@code finapp.payments.attempt{outcome}} series the same judgements feed. */
    public static double attempt(MeterRegistry registry, String outcome) {
        Counter counter = registry.find(PaymentMeters.ATTEMPT).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The legacy {@code finapp.payments.refund{outcome}} series. */
    public static double refund(MeterRegistry registry, String outcome) {
        Counter counter = registry.find(PaymentMeters.REFUND).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
