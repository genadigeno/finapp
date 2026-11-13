package com.finapp.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A jittered first tick (`P16-TSK-018`): never earlier than the interval, under two of them, and spread across it. */
class TickPhaseTest {

    @Test
    @DisplayName("every draw lies from one interval to just under two, never earlier than the schedule's tick was before")
    void everyDrawIsWithinOneInterval() {
        RandomGenerator random = new SplittableRandom(18);
        Duration interval = Duration.ofSeconds(30);
        long lowest = Long.MAX_VALUE;
        long highest = Long.MIN_VALUE;
        for (int draw = 0; draw < 10_000; draw++) {
            long delay = TickPhase.firstDelayMillis(interval, random);
            assertThat(delay).isBetween(30_000L, 59_999L);
            lowest = Math.min(lowest, delay);
            highest = Math.max(highest, delay);
        }
        // Spread, not a constant offset: ten thousand draws reach both ends of the interval.
        assertThat(lowest).as("the lowest draw").isLessThan(30_300L);
        assertThat(highest).as("the highest draw").isGreaterThan(59_700L);
    }

    @Test
    @DisplayName("instances started together spread: ten draws for one interval are not all one value")
    void tenInstancesDoNotTickTogether() {
        RandomGenerator random = new SplittableRandom(10);
        Duration interval = Duration.ofSeconds(5);
        long distinct = java.util.stream.LongStream.range(0, 10)
                .map(instance -> TickPhase.firstDelayMillis(interval, random)).distinct().count();
        assertThat(distinct).isGreaterThan(5);
    }

    @Test
    @DisplayName("the default draw is in range too, and an interval under a millisecond is refused")
    void theDefaultAndTheEdges() {
        assertThat(TickPhase.firstDelayMillis(Duration.ofMillis(1))).isEqualTo(1L);
        assertThat(TickPhase.firstDelayMillis(Duration.ofSeconds(1))).isBetween(1_000L, 1_999L);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> TickPhase.firstDelayMillis(Duration.ZERO));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> TickPhase.firstDelayMillis(Duration.ofNanos(500)));
    }
}
