package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting gauges' reader stops without closing the pool under a read (`P15-TSK-008`): its queued reads are dropped,
 * the read in flight - a statement, which no interrupt reaches - is let end, and the stop returns within its bound
 * whatever the read does. Over the production constructor and its one thread.
 */
@DisplayName("the accounting gauges' reader lets its read in flight end, drops the queue, and stops within a bound (P15-TSK-008)")
class AccountingGaugeRefresherStopTest {

    @Test
    @DisplayName("the read in flight ends before the stop returns; a queued read never runs")
    void theReadInFlightEndsAndTheQueueIsDropped() throws Exception {
        AccountingGaugeRefresher reader = new AccountingGaugeRefresher(false);
        reader.start();
        CountDownLatch inside = new CountDownLatch(1);
        AtomicBoolean letGo = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        AtomicBoolean queuedRan = new AtomicBoolean();
        reader.execute(() -> {
            inside.countDown();
            while (!letGo.get()) {
                Thread.onSpinWait(); // a statement on the wire: an interrupt does not reach it
            }
            ended.set(true);
        });
        reader.execute(() -> queuedRan.set(true));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> stopping = CompletableFuture.supplyAsync(() -> {
            reader.stop();
            return ended.get();
        });
        Thread.sleep(300);
        assertThat(stopping).as("the stop waits for the read in flight").isNotDone();
        letGo.set(true);

        assertThat(stopping.get(10, TimeUnit.SECONDS)).as("the read had ended when the stop returned").isTrue();
        assertThat(queuedRan).as("the queued read was dropped").isFalse();
        assertThat(reader.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a read that outlives the bound is abandoned: the stop returns within its stop bound")
    void aReadPastTheBoundIsAbandoned() throws Exception {
        AccountingGaugeRefresher reader = new AccountingGaugeRefresher(false);
        reader.start();
        CountDownLatch inside = new CountDownLatch(1);
        AtomicBoolean letGo = new AtomicBoolean();
        reader.execute(() -> {
            inside.countDown();
            while (!letGo.get()) {
                Thread.onSpinWait();
            }
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            reader.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertThat(took).isGreaterThanOrEqualTo(AccountingGaugeRefresher.STOP_BOUND);
            assertThat(took).isLessThan(reader.stopBound().plusSeconds(1));
        } finally {
            letGo.set(true);
        }
    }
}
