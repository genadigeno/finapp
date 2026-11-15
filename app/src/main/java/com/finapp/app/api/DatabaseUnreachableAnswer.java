package com.finapp.app.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a request that could not reach the database is told to wait (`P16-REM-002`; {@code THREAT_MODEL.md} section 6 item 61 (i)):
 * {@code Retry-After} in whole seconds, the base plus a random part of the spread, so clients refused together by an outage do not
 * all return in the same second - the shedding's reasoning (ADR-0137). Configuration, never state: every replica answers alike.
 *
 * @param retryAfter whole seconds, at least one
 * @param spread whole seconds, zero or more
 */
public record DatabaseUnreachableAnswer(Duration retryAfter, Duration spread) {

    public DatabaseUnreachableAnswer {
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        Objects.requireNonNull(spread, "spread must not be null");
        if (retryAfter.toSeconds() < 1 || retryAfter.toMillis() % 1000 != 0) {
            throw new IllegalArgumentException("finapp.api.database-unreachable.retry-after is whole seconds, at least one");
        }
        if (spread.isNegative() || spread.toMillis() % 1000 != 0) {
            throw new IllegalArgumentException("finapp.api.database-unreachable.retry-after-spread is whole seconds, zero or more");
        }
    }

    /** The answer a context without its configuration gives - a slice rendering the contract alone. */
    public static final DatabaseUnreachableAnswer DEFAULT = new DatabaseUnreachableAnswer(Duration.ofSeconds(5), Duration.ofSeconds(5));

    /** This answer's {@code Retry-After}, in whole seconds. */
    public long retryAfterSeconds() {
        long spreadSeconds = spread.toSeconds();
        return retryAfter.toSeconds() + (spreadSeconds == 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadSeconds + 1));
    }
}
