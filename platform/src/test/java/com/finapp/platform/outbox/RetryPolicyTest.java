package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The relay's backoff curve and abandonment threshold. */
class RetryPolicyTest {

    private static final RetryPolicy POLICY =
            new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(10), 5);

    @Test
    @DisplayName("the first retry waits the initial backoff, and each one after that doubles")
    void backoffDoubles() {
        assertThat(POLICY.backoffAfter(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(POLICY.backoffAfter(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(POLICY.backoffAfter(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(POLICY.backoffAfter(4)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    @DisplayName("the delay never exceeds the ceiling, however many attempts have been made")
    void backoffIsCapped() {
        // An uncapped exponential eventually schedules the retry for a time nobody is waiting
        // up for: 2^40 milliseconds is thirty-four years.
        assertThat(POLICY.backoffAfter(10)).isEqualTo(Duration.ofSeconds(10));
        assertThat(POLICY.backoffAfter(40)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an absurd attempt count still yields the ceiling, not a short delay")
    void theShiftCannotWrapAround() {
        // Java masks a shift distance to six bits, so `initial << 64` is `initial` - an
        // unguarded doubling would turn the longest backoff into the shortest one. Nothing in
        // the relay can reach these attempt counts today, which is exactly why the guard needs
        // its own test rather than relying on someone noticing later.
        assertThat(POLICY.backoffAfter(64)).isEqualTo(Duration.ofSeconds(10));
        assertThat(POLICY.backoffAfter(65)).isEqualTo(Duration.ofSeconds(10));
        assertThat(POLICY.backoffAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a huge initial backoff is capped rather than overflowing to a negative delay")
    void multiplicationCannotOverflow() {
        RetryPolicy enormous =
                new RetryPolicy(Duration.ofDays(300), Duration.ofDays(400), 10);

        assertThat(enormous.backoffAfter(3)).isEqualTo(Duration.ofDays(400));
        assertThat(enormous.backoffAfter(3)).isPositive();
    }

    @Test
    @DisplayName("a row is abandoned once it reaches the attempt limit, not before")
    void exhaustionIsAtTheLimit() {
        assertThat(POLICY.isExhausted(4)).isFalse();
        assertThat(POLICY.isExhausted(5)).isTrue();
        assertThat(POLICY.isExhausted(6)).isTrue();
    }

    @Test
    @DisplayName("a policy that could never retry or never give up is rejected at construction")
    void nonsensicalPoliciesAreRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new RetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 5));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new RetryPolicy(Duration.ofSeconds(-1), Duration.ofSeconds(1), 5));
        // A ceiling below the floor would make the first retry longer than the maximum.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new RetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(1), 5));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(10), 0));
    }

    @Test
    @DisplayName("asking for the backoff before any attempt has been made is a programming error")
    void backoffRequiresAnAttempt() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> POLICY.backoffAfter(0));
    }

    @Test
    @DisplayName("the default policy tries for tens of minutes, not seconds and not days")
    void theDefaultIsInThePlausibleRange() {
        // Pinned because the value is a judgement about how long a broker outage may last
        // before an event becomes an incident, and a silent change to it changes when anyone
        // finds out about an undeliverable event.
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < RetryPolicy.DEFAULT.maxAttempts(); attempt++) {
            total = total.plus(RetryPolicy.DEFAULT.backoffAfter(attempt));
        }

        assertThat(total).isBetween(Duration.ofMinutes(20), Duration.ofHours(1));
    }
}
