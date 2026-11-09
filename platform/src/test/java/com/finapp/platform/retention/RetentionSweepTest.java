package com.finapp.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.platform.testing.ScriptedSweepConnections;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One batch's transaction, its connection, and the bounds it refuses (`P15-TSK-029`), with no database. */
@DisplayName("a retention sweep's batch: one transaction on one connection, and the bounds it refuses (P15-TSK-029)")
class RetentionSweepTest {

    private static final Duration WINDOW = Duration.ofDays(14);

    @Test
    @DisplayName("the inbox's sweep calls its function with the dedupe window in milliseconds and the batch size, then commits")
    void theInboxSweepSendsTheWindow() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(7);

        int deleted = RetentionSweep.ofExpiredInboxMessages(database::open, WINDOW, 500).sweepBatch();

        assertThat(deleted).isEqualTo(7);
        ScriptedSweepConnections.Batch batch = database.batches().get(0);
        assertThat(batch.statement()).isEqualTo("SELECT platform.sweep_expired_inbox_messages(?, ?)");
        assertThat(batch.boundMillis()).isEqualTo(WINDOW.toMillis());
        assertThat(batch.batchSize()).isEqualTo(500);
        assertThat(batch.committed()).isTrue();
        assertThat(batch.closed()).isTrue();
    }

    @Test
    @DisplayName("the outbox's sweep calls its own function with its own bound")
    void theOutboxSweepSendsItsBound() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(0);
        Duration bound = Duration.ofDays(30);

        RetentionSweep.ofPublishedOutboxEvents(database::open, bound, WINDOW, 100).sweepBatch();

        ScriptedSweepConnections.Batch batch = database.batches().get(0);
        assertThat(batch.statement()).isEqualTo("SELECT platform.sweep_published_outbox_events(?, ?)");
        assertThat(batch.boundMillis()).isEqualTo(bound.toMillis());
    }

    @Test
    @DisplayName("the window counter's sweep calls its own function with its grace and batch size (P15-TSK-022)")
    void theWindowCounterSweepSendsItsGrace() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(3);

        int deleted = RetentionSweep.ofExpiredWindowCounters(database::open, Duration.ofMinutes(1), 1000).sweepBatch();

        assertThat(deleted).isEqualTo(3);
        ScriptedSweepConnections.Batch batch = database.batches().get(0);
        assertThat(batch.statement()).isEqualTo("SELECT platform.sweep_expired_window_counters(?, ?)");
        assertThat(batch.boundMillis()).isEqualTo(Duration.ofMinutes(1).toMillis());
        assertThat(batch.batchSize()).isEqualTo(1000);
        assertThat(RetentionSweep.ofExpiredWindowCounters(database::open, Duration.ofMinutes(1), 1000).table()).isEqualTo("window_counter");
    }

    @Test
    @DisplayName("each batch borrows one connection and returns it before the next: the fleet's connection budget does not move")
    void oneConnectionPerBatch() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().deleting(5, 5, 5);
        RetentionSweep sweep = RetentionSweep.ofExpiredInboxMessages(database::open, WINDOW, 5);

        sweep.sweepBatch();
        sweep.sweepBatch();
        sweep.sweepBatch();

        assertThat(database.peakOpen()).as("never two at once").isEqualTo(1);
        assertThat(database.batches()).hasSize(3).allSatisfy(batch -> assertThat(batch.closed()).isTrue());
    }

    @Test
    @DisplayName("a failed batch rolls back, closes its connection and reports only the SQLState")
    void aFailedBatchRollsBack() {
        ScriptedSweepConnections database = new ScriptedSweepConnections().failingWith("55000");
        RetentionSweep sweep = RetentionSweep.ofExpiredInboxMessages(database::open, WINDOW, 5);

        assertThatExceptionOfType(RetentionSweepException.class)
                .isThrownBy(sweep::sweepBatch)
                .satisfies(failure -> assertThat(failure.sqlState()).isEqualTo("55000"));

        ScriptedSweepConnections.Batch batch = database.batches().get(0);
        assertThat(batch.rolledBack()).isTrue();
        assertThat(batch.committed()).isFalse();
        assertThat(batch.closed()).isTrue();
    }

    @Test
    @DisplayName("the outbox's bound is refused below the inbox's dedupe window, and accepted at it")
    void theOutboxBoundHasAFloor() {
        ScriptedSweepConnections database = new ScriptedSweepConnections();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofPublishedOutboxEvents(database::open, WINDOW.minusMillis(1), WINDOW, 5))
                .withMessageContaining("dedupe window");

        assertThat(RetentionSweep.ofPublishedOutboxEvents(database::open, WINDOW, WINDOW, 5).bound()).isEqualTo(WINDOW);
    }

    @Test
    @DisplayName("a dedupe window at or below the oldest record the broker can redeliver is refused by both sweeps, and just past it accepted")
    void aWindowInsideTheBrokersHorizonIsRefused() {
        ScriptedSweepConnections database = new ScriptedSweepConnections();
        Duration horizon = RetentionSweep.BROKER_REDELIVERY_HORIZON;
        Duration month = Duration.ofDays(30);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofExpiredInboxMessages(database::open, horizon, 5))
                .withMessageContaining("oldest record the broker can redeliver");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofExpiredInboxMessages(database::open, Duration.ofDays(3), 5));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofPublishedOutboxEvents(database::open, month, horizon, 5))
                .withMessageContaining("oldest record the broker can redeliver");

        Duration justPast = horizon.plusMillis(1);
        assertThat(RetentionSweep.ofExpiredInboxMessages(database::open, justPast, 5).bound()).isEqualTo(justPast);
        assertThat(RetentionSweep.ofPublishedOutboxEvents(database::open, month, justPast, 5).bound()).isEqualTo(month);
    }

    @Test
    @DisplayName("a batch outside 1 to the maximum and a bound that is not positive are refused at construction")
    void invalidSettingsAreRefused() {
        ScriptedSweepConnections database = new ScriptedSweepConnections();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofExpiredInboxMessages(database::open, WINDOW, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofExpiredInboxMessages(database::open, WINDOW, RetentionSweep.MAX_BATCH_SIZE + 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofExpiredInboxMessages(database::open, Duration.ZERO, 5));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetentionSweep.ofPublishedOutboxEvents(database::open, Duration.ofDays(30), Duration.ZERO, 5));
    }
}
