package com.finapp.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gauge refresher and its readings (`P15-TSK-024`; ADR-0124 decision 7; PI-014 item 2): a look never reads, one
 * thread reads, a reading is never a number nobody read, an unanswered ask withdraws it, and the refresher restarts.
 * <strong>Every case uses the production thread</strong>: a real executor and reads blocked on latches. Only the clock
 * is the test's, so a bound can be crossed without waiting for it.
 */
@DisplayName("the gauge refresher: every gauge off the scrape thread, restartable (P15-TSK-024)")
class GaugeRefresherTest {

    private static final Duration FLOOR = Duration.ofSeconds(5);
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final MovingClock clock = new MovingClock();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> readOn = new CopyOnWriteArrayList<>();
    private final GaugeRefresher refresher = new GaugeRefresher(true, clock, FLOOR, STALE_AFTER);

    @AfterEach
    void stopTheThread() {
        release.countDown();
        refresher.stop();
    }

    /** A read of {@code value} that records the thread it ran on. */
    private DoubleSupplier recorded(AtomicLong value) {
        return () -> {
            readOn.add(Thread.currentThread().getName());
            return value.get();
        };
    }

    /** A read that holds its thread on {@link #release}: a pool with no free connection, or a socket the network black-holed. */
    private DoubleSupplier blocked(AtomicInteger calls, CountDownLatch inTheRead) {
        return () -> {
            calls.incrementAndGet();
            readOn.add(Thread.currentThread().getName());
            inTheRead.countDown();
            await(release);
            return 7;
        };
    }

    // ------------------------------------------------------------------ never a number nobody read

    @Test
    @DisplayName("before its first read a reading is no value, never a zero; the look asks, the refresher's thread reads, and then the value stands")
    void beforeItsFirstReadAReadingIsNoValue() throws Exception {
        RefreshedReading reading = refresher.track("a reading", recorded(new AtomicLong(0)));
        assertThat(reading.current()).as("not started: nothing read, and no zero").isNaN();
        assertThat(reading.hasBeenRead()).isFalse();

        refresher.start();
        assertThat(refresher.refreshAll(WAIT)).isTrue();
        assertThat(reading.hasBeenRead()).isTrue();
        assertThat(reading.current()).as("a zero the database answered is a number").isZero();
        assertThat(readOn).as("read on the refresher's thread, never the caller's").containsOnly(GaugeRefresher.THREAD);
    }

    @Test
    @DisplayName("a read that answers NaN, and one that throws, are no value; past the floor the next look reads again")
    void aFailedReadIsNoValue() throws Exception {
        AtomicLong value = new AtomicLong(3);
        AtomicInteger mode = new AtomicInteger();
        RefreshedReading reading = refresher.track("a failing reading", () -> switch (mode.get()) {
            case 0 -> value.get();
            case 1 -> Double.NaN;
            default -> throw new IllegalStateException("a value read from the books");
        });
        refresher.start();
        assertThat(refresher.refreshAll(WAIT)).isTrue();
        assertThat(reading.current()).isEqualTo(3.0);

        mode.set(1);
        assertThat(refresher.refreshAll(WAIT)).isTrue();
        assertThat(reading.current()).as("the read's own NaN, never its last number").isNaN();

        mode.set(2);
        assertThat(refresher.refreshAll(WAIT)).isTrue();
        assertThat(reading.current()).as("a read that throws is no value").isNaN();

        mode.set(0);
        clock.advance(FLOOR);
        reading.current();
        awaitValue(reading, 3.0);
    }

    // ------------------------------------------------------------------ off the caller's thread

    @Test
    @DisplayName("with the read blocked, every look answers at once with no value, on the caller's thread nothing is read, and ten concurrent looks start one read")
    void aBlockedReadNeverBlocksALookAndFliesOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inTheRead = new CountDownLatch(1);
        RefreshedReading reading = refresher.track("a blocked reading", blocked(calls, inTheRead));
        refresher.start();
        assertThat(inTheRead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the start asked for the first read").isTrue();

        ExecutorService scrapes = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Double>> looks = new ArrayList<>();
            for (int scrape = 0; scrape < 10; scrape++) {
                looks.add(scrapes.submit(() -> {
                    gate.await();
                    return reading.current();
                }));
            }
            gate.countDown();
            for (Future<Double> look : looks) {
                assertThat(look.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("at once, and no value").isNaN();
            }
            assertThat(calls).as("ten concurrent looks while one read is in flight: one read").hasValue(1);
            assertThat(readOn).containsOnly(GaugeRefresher.THREAD);
        } finally {
            release.countDown();
            scrapes.shutdownNow();
        }
        awaitValue(reading, 7.0);
        assertThat(refresher.refresh(what -> false, WAIT)).as("whatever was queued has run").isTrue();
        assertThat(calls).as("ten looks while one read stood in flight queued no second read").hasValue(1);
    }

    @Test
    @DisplayName("a read that never returns holds the one thread: every reading answers its last value until its ask has waited stale-after, then no value, never its last number")
    void aHungReadTakesEveryReadingToNoValue() throws Exception {
        AtomicInteger hangs = new AtomicInteger();
        CountDownLatch inTheRead = new CountDownLatch(1);
        AtomicLong hangingValue = new AtomicLong(7);
        RefreshedReading hanging = refresher.track("a hanging reading", () -> {
            if (hangs.incrementAndGet() == 2) {
                inTheRead.countDown();
                await(release);
            }
            return hangingValue.get();
        });
        RefreshedReading other = refresher.track("another reading", () -> 8);
        refresher.start();
        awaitValue(hanging, 7.0);
        awaitValue(other, 8.0);

        clock.advance(FLOOR);
        assertThat(hanging.current()).as("past its floor: it asks, and the last value stands").isEqualTo(7.0);
        assertThat(inTheRead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the read is in, on the one thread").isTrue();
        assertThat(other.current()).as("queued behind it, its last value stands").isEqualTo(8.0);

        clock.advance(STALE_AFTER.minusMillis(1));
        assertThat(hanging.current()).as("a millisecond inside stale-after").isEqualTo(7.0);
        assertThat(other.current()).isEqualTo(8.0);
        clock.advance(Duration.ofMillis(1));
        assertThat(hanging.current()).as("its ask has waited stale-after: no value").isNaN();
        assertThat(other.current()).as("and a reading that was never slow: nothing is being read").isNaN();

        release.countDown();
        awaitValue(other, 8.0);
    }

    @Test
    @DisplayName("staleness counts from the unanswered ask, not the value's age: a reading looked at once an hour keeps its number while the refresher answers")
    void aRarelyScrapedReadingKeepsItsNumber() throws Exception {
        AtomicLong value = new AtomicLong(5);
        RefreshedReading reading = refresher.track("a rarely scraped reading", recorded(value));
        refresher.start();
        awaitValue(reading, 5.0);
        clock.advance(Duration.ofHours(1));
        value.set(6);
        assertThat(reading.current()).as("an hour old, asked again just now: it stands").isEqualTo(5.0);
        awaitValue(reading, 6.0);
    }

    @Test
    @DisplayName("a clock stepped back asks again, and an outstanding ask answers no value until a read completes")
    void aClockSteppedBackAsksAgain() throws Exception {
        AtomicLong value = new AtomicLong(1);
        RefreshedReading reading = refresher.track("a stepped reading", recorded(value));
        refresher.start();
        awaitValue(reading, 1.0);
        value.set(2);
        clock.advance(Duration.ofHours(-3));
        reading.current();
        awaitValue(reading, 2.0);
    }

    // ------------------------------------------------------------------ the lifecycle: PI-014 item 2

    @Test
    @DisplayName("start, stop, start: every reading answers a number read after the restart (PI-014 item 2: the executor was made once, in the constructor, and a stopped one refused every read for good)")
    void startStopStartLeavesEveryReadingANumber() throws Exception {
        List<AtomicLong> values = new ArrayList<>();
        List<RefreshedReading> readings = new ArrayList<>();
        for (int gauge = 0; gauge < 9; gauge++) {
            AtomicLong value = new AtomicLong(gauge);
            values.add(value);
            readings.add(refresher.track("gauge " + gauge, value::get));
        }
        refresher.start();
        assertThat(refresher.refreshAll(WAIT)).isTrue();
        assertThat(readings).allSatisfy(reading -> assertThat(reading.current()).isNotNaN());

        refresher.stop();
        assertThat(refresher.isRunning()).isFalse();
        values.forEach(value -> value.addAndGet(100));
        refresher.start();
        assertThat(refresher.isRunning()).isTrue();
        assertThat(refresher.refreshAll(WAIT)).as("the restarted thread takes reads").isTrue();
        for (int gauge = 0; gauge < readings.size(); gauge++) {
            assertThat(readings.get(gauge).current()).as("gauge %s, read after the restart", gauge).isEqualTo(gauge + 100.0);
        }
        clock.advance(STALE_AFTER.plus(FLOOR));
        assertThat(refresher.refreshAll(WAIT)).as("and it keeps answering past every bound").isTrue();
        assertThat(readings).allSatisfy(reading -> assertThat(reading.current()).isNotNaN());
    }

    @Test
    @DisplayName("a read queued at the stop is dropped and its flag put down, so the next start takes its reading's ask")
    void aReadDroppedAtTheStopDoesNotWedgeItsReading() throws Exception {
        CountDownLatch inTheRead = new CountDownLatch(1);
        AtomicLong value = new AtomicLong(4);
        GaugeRefresher off = new GaugeRefresher(false, clock, FLOOR, STALE_AFTER);
        try {
            RefreshedReading queued = off.track("a queued reading", value::get);
            RefreshedReading blocker = off.track("a blocker", () -> {
                inTheRead.countDown();
                await(release);
                return 1;
            });
            off.start();
            blocker.current();
            assertThat(inTheRead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(queued.current()).as("queued behind the blocker").isNaN();
            off.stop();
            off.start();
            assertThat(queued.current()).as("its read was dropped; the restart takes a new ask").isNaN();
            awaitValue(queued, 4.0);
        } finally {
            release.countDown();
            off.stop();
        }
    }

    @Test
    @DisplayName("stopped and never restarted: a look answers the last value until its ask has waited stale-after, then no value - never a number for good")
    void aStoppedRefresherWithdrawsItsValues() throws Exception {
        RefreshedReading reading = refresher.track("a reading", () -> 9);
        refresher.start();
        awaitValue(reading, 9.0);
        refresher.stop();
        clock.advance(FLOOR);
        assertThat(reading.current()).as("asked, refused, and inside stale-after").isEqualTo(9.0);
        clock.advance(STALE_AFTER);
        assertThat(reading.current()).isNaN();
        assertThat(refresher.refreshAll(WAIT)).as("a stopped refresher takes nothing").isFalse();
    }

    @Test
    @DisplayName("with the first reads on, the start asks every reading and a reading tracked while running is asked at once; with them off, the start reads nothing and a look still reads on the thread")
    void theFirstReads() throws Exception {
        AtomicLong value = new AtomicLong(2);
        RefreshedReading before = refresher.track("tracked before the start", recorded(value));
        refresher.start();
        awaitRead(before);
        RefreshedReading after = refresher.track("tracked while running", recorded(value));
        awaitRead(after);
        assertThat(readOn).containsOnly(GaugeRefresher.THREAD);

        GaugeRefresher off = new GaugeRefresher(false, clock, FLOOR, STALE_AFTER);
        try {
            AtomicInteger reads = new AtomicInteger();
            RefreshedReading quiet = off.track("no first read", () -> reads.incrementAndGet());
            off.start();
            assertThat(off.refreshAll(WAIT)).isTrue();
            assertThat(reads).as("refreshAll reads it, once").hasValue(1);
            assertThat(quiet.current()).isEqualTo(1.0);
        } finally {
            off.stop();
        }
        GaugeRefresher idle = new GaugeRefresher(false, clock, FLOOR, STALE_AFTER);
        try {
            AtomicInteger reads = new AtomicInteger();
            idle.track("no first read", reads::incrementAndGet);
            RefreshedReading probe = idle.track("a later look", () -> 1);
            idle.start();
            probe.current();
            // One thread, in order: a read the start had asked for would have run before this one.
            awaitRead(probe);
            assertThat(reads).as("no background reader at the start").hasValue(0);
        } finally {
            idle.stop();
        }
    }

    @Test
    @DisplayName("the thread is named and a daemon, so it never holds a JVM up; a second start while running makes no second thread")
    void theThreadIsNamedAndDaemon() throws Exception {
        CountDownLatch inTheRead = new CountDownLatch(1);
        refresher.track("a blocked reading", blocked(new AtomicInteger(), inTheRead));
        refresher.start();
        refresher.start();
        assertThat(inTheRead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        List<Thread> threads = Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals(GaugeRefresher.THREAD))
                .filter(Thread::isAlive).toList();
        assertThat(threads).as("one thread, held in the read, a daemon").singleElement().matches(Thread::isDaemon);
    }

    // ------------------------------------------------------------------

    private void awaitValue(RefreshedReading reading, double expected) throws InterruptedException {
        for (long tick = 0; tick < WAIT.toMillis() / 10; tick++) {
            if (reading.current() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(reading.current()).as("the refresher's thread read it").isEqualTo(expected);
    }

    private void awaitRead(RefreshedReading reading) throws InterruptedException {
        for (long tick = 0; tick < WAIT.toMillis() / 10 && !reading.hasBeenRead(); tick++) {
            Thread.sleep(10);
        }
        assertThat(reading.hasBeenRead()).as("%s was read", reading.what()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class MovingClock extends Clock {
        private volatile Instant now = Instant.parse("2097-04-05T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
