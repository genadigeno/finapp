package com.finapp.app.shedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.platform.api.PlatformErrorCode;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shedding policy, alone (`P16-TSK-016`, ADR-0137): its three rules, its permits, its answer and its refusals at start. */
@DisplayName("one instance's shedding policy: the pool, the permits, the deadline (P16-TSK-016)")
class LoadSheddingTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SheddingFixtures.Pool pool = new SheddingFixtures.Pool();
    private final SheddingFixtures.MovingClock clock = new SheddingFixtures.MovingClock();

    private LoadShedding shedding() {
        return shedding(SheddingFixtures.defaults(), SheddingFixtures.draws(false));
    }

    private LoadShedding shedding(LoadShedding.Bounds bounds, RandomGenerator jitter) {
        return new LoadShedding(pool, bounds, clock, jitter, new SheddingMeters(registry));
    }

    private double meter(String type, String outcome) {
        return registry.get(SheddingMeters.NAME).tag("type", type).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("a request of either class is shed while the pool's queue is at its threshold, and admitted just below it")
    void thePoolsQueueShedsEveryClass() {
        LoadShedding shedding = shedding();
        pool.waiting = 7;
        for (LoadShedding.Type type : LoadShedding.Type.values()) {
            assertThatExceptionOfType(RequestShed.class).isThrownBy(() -> shedding.admit(type))
                    .satisfies(shed -> assertThat(shed.errorCode()).isEqualTo(PlatformErrorCode.OVERLOADED));
        }
        assertThat(shedding.commandsInFlight()).as("a command shed on the pool took no permit").isZero();
        assertThat(meter("command", "pool")).isEqualTo(1);
        assertThat(meter("read", "pool")).isEqualTo(1);

        pool.waiting = 6;
        assertThat(shedding.admit(LoadShedding.Type.READ).holdsPermit()).isFalse();
        assertThat(shedding.admit(LoadShedding.Type.COMMAND).holdsPermit()).isTrue();
        assertThat(meter("read", "admitted")).isEqualTo(1);
        assertThat(meter("command", "admitted")).isEqualTo(1);
    }

    @Test
    @DisplayName("past its permits a command is shed at once, never queued; reads take no permit; a permit given back admits the next")
    void commandsAreBoundedByTheirPermits() {
        LoadShedding shedding = shedding();
        List<LoadShedding.Admission> held = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            held.add(shedding.admit(LoadShedding.Type.COMMAND));
        }
        assertThat(shedding.commandsInFlight()).isEqualTo(5);

        assertThatExceptionOfType(RequestShed.class).isThrownBy(() -> shedding.admit(LoadShedding.Type.COMMAND));
        assertThat(shedding.commandsInFlight()).as("the refused command gave back what it took").isEqualTo(5);
        assertThat(meter("command", "permits")).isEqualTo(1);
        assertThat(shedding.admit(LoadShedding.Type.READ).holdsPermit()).as("a read is bounded by the pool alone").isFalse();

        shedding.release(held.remove(0));
        assertThat(shedding.admit(LoadShedding.Type.COMMAND).holdsPermit()).isTrue();
        assertThat(shedding.commandsInFlight()).isEqualTo(5);
    }

    @Test
    @DisplayName("a command with less than one borrow's wait left of its deadline is shed; one with exactly that is not; a read never")
    void theDeadlineLeavesACommandOneBorrow() {
        LoadShedding shedding = shedding();
        LoadShedding.Admission command = shedding.admit(LoadShedding.Type.COMMAND);
        LoadShedding.Admission read = shedding.admit(LoadShedding.Type.READ);

        clock.advance(Duration.ofSeconds(5));
        shedding.requireTimeLeft(command);
        clock.advance(Duration.ofMillis(1));
        assertThatExceptionOfType(RequestShed.class).isThrownBy(() -> shedding.requireTimeLeft(command));
        assertThat(meter("command", "deadline")).isEqualTo(1);
        clock.advance(Duration.ofMinutes(5));
        shedding.requireTimeLeft(read);
        assertThat(meter("read", "deadline")).isZero();
    }

    @Test
    @DisplayName("Retry-After is whole seconds from the base to the base plus the spread, both ends reachable, and no figure of the load")
    void retryAfterIsJitteredWithinItsBounds() {
        LoadShedding.Bounds bounds = SheddingFixtures.defaults();
        pool.waiting = 99;
        assertThatExceptionOfType(RequestShed.class).isThrownBy(() -> shedding(bounds, SheddingFixtures.draws(false)).admit(LoadShedding.Type.READ))
                .satisfies(shed -> assertThat(shed.retryAfterSeconds()).isEqualTo(1));
        assertThatExceptionOfType(RequestShed.class).isThrownBy(() -> shedding(bounds, SheddingFixtures.draws(true)).admit(LoadShedding.Type.READ))
                .satisfies(shed -> assertThat(shed.retryAfterSeconds()).isEqualTo(3));
        LoadShedding real = shedding(bounds, new java.security.SecureRandom());
        for (int i = 0; i < 200; i++) {
            assertThatExceptionOfType(RequestShed.class).isThrownBy(() -> real.admit(LoadShedding.Type.COMMAND))
                    .satisfies(shed -> {
                        assertThat(shed.retryAfterSeconds()).isBetween(1L, 3L);
                        assertThat(shed.getMessage()).isEqualTo("api.Overloaded");
                        assertThat(shed.getStackTrace()).isEmpty();
                    });
        }
    }

    @Test
    @DisplayName("refused at start: permits not fewer than the pool's connections, a deadline within one borrow's wait, a zero bound")
    void boundsThatDoNotFitThePoolAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> shedding(
                new LoadShedding.Bounds(7, 7, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ZERO), SheddingFixtures.draws(false)))
                .withMessageContaining("fewer than the writer pool's 7 connections");
        assertThatIllegalArgumentException().isThrownBy(() -> shedding(
                new LoadShedding.Bounds(7, 5, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ZERO), SheddingFixtures.draws(false)))
                .withMessageContaining("must exceed the pool's borrow wait");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoadShedding.Bounds(0, 5, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoadShedding.Bounds(7, 0, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoadShedding.Bounds(7, 5, Duration.ofSeconds(10), Duration.ZERO, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoadShedding.Bounds(7, 5, Duration.ofSeconds(10), Duration.ofMillis(1500), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LoadShedding.Bounds(7, 5, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(-1)));
        assertThat(shedding(new LoadShedding.Bounds(7, 6, Duration.ofMillis(5001), Duration.ofSeconds(1), Duration.ZERO),
                SheddingFixtures.draws(false)).bounds().commandPermits()).as("the widest that fits is accepted").isEqualTo(6);
    }

    @Test
    @DisplayName("the pool is read from HikariCP itself, through a wrapper: its size and wait as built, and no queue before it starts")
    void thePoolIsReadFromHikari() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setMaximumPoolSize(7);
            hikari.setConnectionTimeout(5000);
            PoolQueue read = PoolSources.throughAWrapper(hikari);

            assertThat(read.size()).isEqualTo(7);
            assertThat(read.borrowWait()).isEqualTo(Duration.ofSeconds(5));
            assertThat(read.waiting()).as("a pool that has not started has nobody waiting on it").isZero();
        }
    }

    @Test
    @DisplayName("a data source that is not HikariCP is refused at start: a door that cannot read the queue would admit everything")
    void aPoolItCannotReadIsRefused() {
        assertThatIllegalStateException().isThrownBy(PoolSources::fromAnotherPool).withMessageContaining("not a HikariCP pool");
    }
}
