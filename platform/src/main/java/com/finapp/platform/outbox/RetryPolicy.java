package com.finapp.platform.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * How long the relay waits after a failed publication, and when it stops trying.
 *
 * <p><strong>Why backoff is not optional.</strong> Without it, an unavailable broker turns the
 * relay into a hot loop: every poll retries every pending row immediately, so the moment the
 * broker is in trouble the platform starts hammering it, and the database along with it. The
 * failure mode is a retry storm that outlives the fault that caused it.
 *
 * <p><strong>Why the delay grows.</strong> A momentary blip should cost a second, not a minute,
 * or ordinary transient failures would add latency to every event. A sustained outage should
 * cost minutes, not milliseconds, or the retries become the incident. Doubling with a ceiling is
 * the standard shape because it does both, and the ceiling matters as much as the growth: an
 * uncapped exponential eventually schedules the retry for a time nobody is waiting up for.
 *
 * <p><strong>No jitter, and that is a decision rather than an omission.</strong> Jitter spreads
 * a synchronised herd of retries, and the herd here is limited by construction: the relay holds
 * one advisory lock per aggregate, so instances diverge onto different aggregates rather than
 * colliding on the same rows. Adding randomness would also make the backoff irreproducible in a
 * test without injecting a source of randomness for it. It becomes worth adding when there is a
 * measured herd, which is a Phase 16 question, not a Phase 0 guess.
 *
 * <p>Durations are integer milliseconds throughout. A floating-point delay on a path that
 * decides whether a financial event is published is the same category of mistake as
 * floating-point money ({@code INV-MON-01}), and the rule that enforces that would reject it.
 *
 * @param initialBackoff the wait after the first failed attempt
 * @param maxBackoff the ceiling the doubling never exceeds
 * @param maxAttempts how many attempts a row gets before it is abandoned to the poison path
 */
public record RetryPolicy(Duration initialBackoff, Duration maxBackoff, int maxAttempts) {

    /**
     * A second, doubling to five minutes, giving up after fourteen attempts.
     *
     * <p>That curve spends roughly half an hour trying: long enough that a broker restart or a
     * short network partition resolves itself without an operator, short enough that a genuinely
     * undeliverable event is visible within a shift rather than discovered during a
     * reconciliation weeks later.
     *
     * <p>The attempt count and the curve are not independent — with a five-minute ceiling, ten
     * attempts is eight and a half minutes, not the half hour it looks like. {@code RetryPolicyTest}
     * pins the total rather than the count, because the count is the number that gets adjusted
     * and the window is the thing that actually matters.
     */
    public static final RetryPolicy DEFAULT =
            new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), 14);

    public RetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException(
                    "initialBackoff must be positive but was " + initialBackoff);
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "maxBackoff " + maxBackoff + " must be at least initialBackoff " + initialBackoff);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 but was " + maxAttempts);
        }
    }

    /**
     * How long to wait after {@code attempts} failed attempts.
     *
     * @param attempts the number of attempts made so far, including the one that just failed
     */
    public Duration backoffAfter(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1 but was " + attempts);
        }
        long initial = initialBackoff.toMillis();
        long ceiling = maxBackoff.toMillis();
        int doublings = attempts - 1;
        // Guarded rather than computed and clamped afterwards: shifting by 64 or more in Java
        // silently masks the shift distance, so `initial << 64` is `initial` - a very long
        // backoff would quietly become a very short one.
        if (doublings >= Long.SIZE - 1 || initial > ceiling >> doublings) {
            return maxBackoff;
        }
        return Duration.ofMillis(initial << doublings);
    }

    /** Whether a row that has now been attempted {@code attempts} times should be abandoned. */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
