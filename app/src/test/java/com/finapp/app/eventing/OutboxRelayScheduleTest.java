package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.lifecycle.WorkHold;
import com.finapp.platform.outbox.RelayPollResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relay schedule's tick (`P16-TSK-013`, ADR-0136): a cycle, and another at once while each comes back with its batch full and
 * nothing failed; a short cycle, a failed attempt, a thrown cycle, a stop begun or a hold come on ends the tick. What a cycle does
 * is the relay's ({@code OutboxRelaySlotTest}, and {@code OutboxRelayTest} on the database); what only this class can see is how
 * often one tick asks for it. Hermetic: the relay is a scripted sequence of cycles; the schedule is never started unless the case
 * is about its stop.
 */
@DisplayName("the outbox relay's tick drains while its batch comes back full, and stops on anything else (P16-TSK-013)")
class OutboxRelayScheduleTest {

    private static final RelayPollResult FULL = new RelayPollResult(8, 8, 0, 0, true);
    private static final RelayPollResult SHORT = new RelayPollResult(3, 5, 0, 0, false);
    private static final RelayPollResult FAILED = new RelayPollResult(8, 7, 1, 0, false);

    @Test
    @DisplayName("full cycles run back to back, and the first short one ends the tick; every cycle's outcomes counted")
    void drainsWhileFull() {
        Scripted relay = new Scripted(FULL, FULL, SHORT, FULL);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxRelaySchedule schedule = new OutboxRelaySchedule(relay, Duration.ofSeconds(1), registry);

        schedule.tick();

        assertThat(relay.cycles()).as("two full cycles and the short one, not the fourth").isEqualTo(3);
        assertThat(registry.counter("finapp.outbox.publication", "outcome", "published").count()).isEqualTo(21.0);
    }

    @Test
    @DisplayName("a cycle that failed an attempt ends the tick: a broker in trouble keeps the interval")
    void aFailedCycleEndsTheTick() {
        Scripted relay = new Scripted(FULL, FAILED, FULL);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxRelaySchedule schedule = new OutboxRelaySchedule(relay, Duration.ofSeconds(1), registry);

        schedule.tick();

        assertThat(relay.cycles()).isEqualTo(2);
        assertThat(registry.counter("finapp.outbox.publication", "outcome", "failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a thrown cycle ends the tick and is logged, as before; the next tick tries again")
    void aThrownCycleEndsTheTick() {
        AtomicInteger cycles = new AtomicInteger();
        OutboxRelaySchedule schedule = new OutboxRelaySchedule(() -> {
            cycles.incrementAndGet();
            throw new IllegalStateException("the database went away");
        }, Duration.ofSeconds(1), new SimpleMeterRegistry());

        schedule.tick();
        schedule.tick();

        assertThat(cycles).hasValue(2);
    }

    @Test
    @DisplayName("once the schedule's stop has begun, the cycle in flight is the last: no further cycle however full it came back")
    void aStopEndsTheDrain() {
        AtomicInteger cycles = new AtomicInteger();
        OutboxRelaySchedule schedule = new OutboxRelaySchedule(() -> {
            cycles.incrementAndGet();
            return FULL;
        }, Duration.ofHours(1), new SimpleMeterRegistry());
        schedule.start();
        schedule.stop();
        assertThat(schedule.drain().stopping()).isTrue();

        schedule.tick();

        assertThat(cycles).hasValue(1);
    }

    @Test
    @DisplayName("while the schedule's hold is on, a full cycle takes no further one")
    void aHoldEndsTheDrain() {
        AtomicInteger cycles = new AtomicInteger();
        OutboxRelaySchedule schedule = new OutboxRelaySchedule(() -> {
            cycles.incrementAndGet();
            return FULL;
        }, Duration.ofSeconds(1), new SimpleMeterRegistry());
        schedule.drain().heldBy(new WorkHold() {
            @Override
            public boolean held() {
                return true;
            }

            @Override
            public void whenHeld(Runnable onHeld) {
                // Already on.
            }
        });

        schedule.tick();

        assertThat(cycles).hasValue(1);
    }

    /** A relay answering a fixed sequence of cycles, then idle. */
    private static final class Scripted implements OutboxRelaySchedule.Poll {

        private final Deque<RelayPollResult> results;
        private int cycles;

        Scripted(RelayPollResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public RelayPollResult pollOnce() {
            cycles++;
            RelayPollResult next = results.poll();
            return next == null ? RelayPollResult.idle() : next;
        }

        int cycles() {
            return cycles;
        }
    }
}
