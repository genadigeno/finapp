package com.finapp.accounting;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a {@code LOCKED} period may stand with no close run under its present lock before that is a stalled check leg
 * and not the ordinary gap (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 8.1 T-check's recorded deviation, whose standing
 * condition this is; `P14-TSK-011`'s review; the owner's decision of 2026-11-02). The run is born by the schedule, not in
 * T-lock, so {@code LOCKED} with no run is normal for at most one poll of the check leg. The bound is a number of those
 * polls, and is refused at start-up under {@link #MIN_POLLS}: a bound a healthy fleet can cross would be an alert nobody
 * believes.
 *
 * @param checksPoll the check leg's poll - {@code finapp.accounting.close.checks-poll}
 * @param polls how many of them - {@code finapp.accounting.close.run-stalled-polls}
 */
public record CloseRunStall(Duration checksPoll, int polls) {

    /** Ten polls: five minutes on the standard thirty-second poll. */
    public static final int STANDARD_POLLS = 10;

    /** Below three polls, one missed tick and one slow start would page someone. */
    public static final int MIN_POLLS = 3;

    public CloseRunStall {
        Objects.requireNonNull(checksPoll, "checksPoll");
        if (checksPoll.toMillis() < 1) {
            throw new IllegalArgumentException("the check leg's poll is positive");
        }
        if (polls < MIN_POLLS) {
            throw new IllegalArgumentException("a stalled close run is at least " + MIN_POLLS
                    + " polls of the check leg - fewer would trip on the ordinary gap");
        }
    }

    /** The bound itself. */
    public Duration after() {
        return checksPoll.multipliedBy(polls);
    }
}
