package com.finapp.platform.inbox;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a record may fail for a reason of its own before it is set aside (`P15-TSK-028`).
 *
 * <p><strong>Both must be spent</strong>, and both are judged on the row in PostgreSQL, so N instances and a restart all see one
 * count. A count alone is spent in seconds at the consumer's one-second backoff, so a failure of a kind {@link InboxFailures}
 * has not met - a slow dependency, a rolling restart behind a handler - would park a valid record; the elapsed floor rides those
 * out. The price is what the floor says: a partition stands still that long for one truly poison record.
 *
 * @param attempts the failures of the record's own, at least two: a record never parks on its first failure
 * @param failingFor how long since the first of them, positive
 */
public record ParkingPolicy(int attempts, Duration failingFor) {

    public ParkingPolicy {
        Objects.requireNonNull(failingFor, "failingFor must not be null");
        if (attempts < 2) {
            throw new IllegalArgumentException("a record is never parked on its first failure: " + attempts);
        }
        if (failingFor.isNegative() || failingFor.isZero()) {
            throw new IllegalArgumentException("failingFor must be positive: " + failingFor);
        }
    }
}
