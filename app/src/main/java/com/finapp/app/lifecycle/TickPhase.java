package com.finapp.app.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * When a schedule's first tick falls, for a schedule that reaches an external dependency from every instance (`P16-TSK-018`;
 * {@code PHASE_16_PLAN.md} section 8, "jitter where several instances retry the same dependency at once";
 * {@code docs/architecture/TIMEOUTS_AND_RETRIES.md} section 4).
 *
 * <p>Every schedule ticks on a fixed delay. Started with a first delay of exactly one interval, instances that start together - a
 * cold start, several replicas added at once, a fleet made ready together after an outage of their own - tick in step, and a fixed
 * delay keeps them in step: each tick drifts only by its own duration. Each of their ticks then asks the same provider, or the
 * broker, at the same moment, every interval: inquiries, re-asks and re-dispatches under the same key arrive at a recovering
 * dependency together. The first tick is therefore drawn uniformly from one interval to just under two, once per schedule start,
 * so instances started together spread over an interval and stay spread.
 *
 * <p>Nothing correct rests on it. Every claim, permit and transition these schedules make is the database's (a {@code SKIP LOCKED}
 * claim, a conditional update, an advisory lock), so ticks that happen to coincide are as correct as ticks that do not; the draw
 * only shapes when a dependency is asked. It is never earlier than the interval the schedule had before, so nothing that waited a
 * first interval waits less. The draw is per instance and holds nothing between instances
 * ({@code NoSingleInstanceAssumptionRulesTest}).
 */
public final class TickPhase {

    private TickPhase() {}

    /**
     * The first tick's delay, in milliseconds: the interval plus a uniform draw from {@code [0, interval)}.
     *
     * @param interval the schedule's fixed delay, positive
     * @return a delay from {@code interval} to just under twice it
     */
    public static long firstDelayMillis(Duration interval) {
        return firstDelayMillis(interval, RandomGenerator.getDefault());
    }

    /**
     * As {@link #firstDelayMillis(Duration)}, from the given generator (its own test's).
     *
     * @param interval the schedule's fixed delay, positive
     * @param random where the draw comes from
     * @return a delay from {@code interval} to just under twice it
     */
    static long firstDelayMillis(Duration interval, RandomGenerator random) {
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(random, "random must not be null");
        long millis = interval.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException("a schedule's interval must be at least a millisecond: " + interval);
        }
        return Math.addExact(millis, random.nextLong(millis));
    }
}
