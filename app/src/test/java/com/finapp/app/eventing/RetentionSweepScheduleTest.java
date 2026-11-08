package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.app.lifecycle.ShutdownOrder;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.retention.RetentionSweep;
import com.finapp.platform.testing.ScriptedSweepConnections;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two retention schedules (`P15-TSK-029`), and their bounds read through the same bean methods the application is wired by.
 * What the batch selects is {@code RetentionSweepMigrationTest}'s.
 */
@DisplayName("the retention sweep schedules (P15-TSK-029)")
class RetentionSweepScheduleTest {

    private static final Duration WINDOW = Duration.ofDays(14);

    private static final Duration INTERVAL = Duration.ofMinutes(5);

    private static final int BATCH = 10;

    private final EventingBeans beans = new EventingBeans();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RetentionSweepMeters meters = beans.retentionSweepMeters(registry);

    private RetentionSweepSchedule schedule(ScriptedSweepConnections database) {
        return new RetentionSweepSchedule(
                "test-sweep", RetentionSweep.ofExpiredInboxMessages(database::open, WINDOW, BATCH), INTERVAL, meters);
    }

    private double swept(String table) {
        return registry.get("finapp.retention.swept").tag("type", table).counter().count();
    }

    private double failed(String table) {
        return registry.get("finapp.retention.sweep.failed").tag("type", table).counter().count();
    }

    private InboxConsumer<Connection> inbox(Duration retention) {
        return beans.inboxConsumer(Clock.systemUTC(), retention);
    }

    @Test
    @DisplayName("the inbox's bound is the retention the consumer writes with, whatever it is set to: nothing else can set it")
    void theInboxBoundIsTheConsumersRetention() {
        for (Duration retention : List.of(Duration.ofDays(14), Duration.ofDays(21), RetentionSweep.BROKER_REDELIVERY_HORIZON.plusMillis(1))) {
            ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(0);

            RetentionSweepSchedule schedule =
                    beans.inboxRetentionSweepSchedule(database::open, inbox(retention), INTERVAL, BATCH, meters);
            schedule.sweepOnce();

            assertThat(schedule.sweep().bound()).isEqualTo(retention);
            assertThat(database.batches().get(0).boundMillis())
                    .as("the bound the database compares with")
                    .isEqualTo(retention.toMillis());
        }
    }

    @Test
    @DisplayName("a finapp.inbox.retention inside the broker's redelivery horizon stops both sweeps' beans, so the instance does not start")
    void aRetentionInsideTheBrokersHorizonRefusesToStart() {
        ScriptedSweepConnections database = new ScriptedSweepConnections();
        InboxConsumer<Connection> threeDays = inbox(Duration.ofDays(3));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> beans.inboxRetentionSweepSchedule(database::open, threeDays, INTERVAL, BATCH, meters))
                .withMessageContaining("oldest record the broker can redeliver");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> beans.outboxRetentionSweepSchedule(database::open, threeDays, Duration.ofDays(30), INTERVAL, BATCH, meters))
                .withMessageContaining("oldest record the broker can redeliver");
    }

    @Test
    @DisplayName("the outbox's bound is refused below the consumer's retention, so an audited replay can always read its window")
    void theOutboxBoundIsRefusedBelowTheConsumersRetention() {
        ScriptedSweepConnections database = new ScriptedSweepConnections();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> beans.outboxRetentionSweepSchedule(
                        database::open, inbox(WINDOW), WINDOW.minusSeconds(1), INTERVAL, BATCH, meters))
                .withMessageContaining("dedupe window");
        assertThat(beans.outboxRetentionSweepSchedule(database::open, inbox(WINDOW), WINDOW, INTERVAL, BATCH, meters).sweep().bound())
                .isEqualTo(WINDOW);
    }

    @Test
    @DisplayName("a tick repeats full batches and stops at the first short one, counting every row")
    void aTickStopsAtTheFirstShortBatch() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(BATCH, BATCH, 3);

        int total = schedule(database).sweepOnce();

        assertThat(total).isEqualTo(23);
        assertThat(database.batches()).hasSize(3);
        assertThat(swept("inbox_message")).isEqualTo(23);
    }

    @Test
    @DisplayName("a tick is bounded: full batches stop at the cap, and the next tick continues")
    void aTickIsBounded() {
        int[] full = new int[RetentionSweepSchedule.MAX_BATCHES + 1];
        java.util.Arrays.fill(full, BATCH);
        full[RetentionSweepSchedule.MAX_BATCHES] = 0;
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(full);
        RetentionSweepSchedule schedule = schedule(database);

        schedule.sweepOnce();
        assertThat(database.batches()).hasSize(RetentionSweepSchedule.MAX_BATCHES);

        schedule.sweepOnce();
        assertThat(database.batches()).hasSize(RetentionSweepSchedule.MAX_BATCHES + 1);
    }

    @Test
    @DisplayName("a stopping schedule takes no further batch: the rows wait for the next tick, on any instance (P15-TSK-009)")
    void aStoppingScheduleTakesNoFurtherBatch() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(BATCH, BATCH, 3);
        RetentionSweepSchedule schedule = schedule(database);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        schedule.drain().started(executor);

        assertThat(schedule.sweepOnce()).isZero();
        assertThat(database.batches()).isEmpty();
    }

    @Test
    @DisplayName("an interrupted sweep resumes: a batch that fails is counted, the batches before it stand, the next tick goes on")
    void anInterruptedSweepResumes() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(BATCH).failingWith("08006").deleting(4);
        RetentionSweepSchedule schedule = schedule(database);

        schedule.sweepQuietly();

        assertThat(swept("inbox_message")).as("the batch before the failure stands").isEqualTo(BATCH);
        assertThat(failed("inbox_message")).isEqualTo(1);

        schedule.sweepQuietly();

        assertThat(swept("inbox_message")).isEqualTo(BATCH + 4);
        assertThat(failed("inbox_message")).isEqualTo(1);
    }

    @Test
    @DisplayName("both tables' series exist before any sweep, on an instance with no schedule")
    void theMetersAreEager() {
        assertThat(swept("outbox_event")).isZero();
        assertThat(swept("inbox_message")).isZero();
        assertThat(failed("outbox_event")).isZero();
        assertThat(failed("inbox_message")).isZero();
    }

    @Test
    @DisplayName("it stops in the schedules' phase, by its own drain")
    void itDrainsWithTheSchedules() {
        RetentionSweepSchedule schedule = schedule(new ScriptedSweepConnections());

        assertThat(schedule.getPhase()).isEqualTo(ShutdownOrder.SCHEDULES);
        assertThat(schedule.drain().name()).isEqualTo("test-sweep");
        assertThat(schedule.stopBound()).isEqualTo(schedule.drain().stopBound());
    }

    @Test
    @DisplayName("each of the two beans names its own drain, so a stopping instance's log can tell them apart")
    void theTwoBeansHaveDistinctDrains() {
        ScriptedSweepConnections database = new ScriptedSweepConnections();

        assertThat(beans.outboxRetentionSweepSchedule(database::open, inbox(WINDOW), Duration.ofDays(30), INTERVAL, BATCH, meters).drain().name())
                .isEqualTo("outbox-retention-sweep");
        assertThat(beans.inboxRetentionSweepSchedule(database::open, inbox(WINDOW), INTERVAL, BATCH, meters).drain().name())
                .isEqualTo("inbox-retention-sweep");
    }

    @Test
    @DisplayName("an interval that is not positive is refused")
    void anIntervalMustBePositive() {
        RetentionSweep sweep = RetentionSweep.ofExpiredInboxMessages(new ScriptedSweepConnections()::open, WINDOW, BATCH);

        assertThatIllegalArgumentException().isThrownBy(() -> new RetentionSweepSchedule("n", sweep, Duration.ZERO, meters));
    }

    @Test
    @DisplayName("the consumer the bound is read from is the store's real one, so the sweep and the writer share the value")
    void theConsumerCarriesTheRetentionItWasBuiltWith() {
        assertThat(new InboxConsumer<Connection>(new JdbcInboxRecordStore(), Clock.systemUTC(), WINDOW).retention()).isEqualTo(WINDOW);
    }
}
