package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What holds a gauge's read away from a scrape (`P14-TSK-020`; `P14-TSK-017`'s review, M-9; its own review, I-1): {@code current()} never touches the pool, one read per gauge, a floor, four floors, a statement timeout on a read-only snapshot, and no value on any failure.
 * <strong>The cases about the thread use the production refresher</strong>, a real thread and a connection source blocked on a latch; only the cases about a reading's arithmetic read in line. That production is wired so is {@code AccountingGaugeWiringTest}'s.
 */
@DisplayName("one accounting gauge's read of the database, off the scrape and bounded (P14-TSK-020)")
class AccountingGaugeReadTest {

    private static final Duration FLOOR = Duration.ofSeconds(15);
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final MovingClock clock = new MovingClock();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicLong value = new AtomicLong(7);
    private final List<SnapshotSession> sessions = new CopyOnWriteArrayList<>();
    private final List<String> openedOn = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AccountingGaugeRefresher inLine = new AccountingGaugeRefresher(Runnable::run);
    private final AccountingGaugeRefresher production = new AccountingGaugeRefresher();
    private boolean unreachable;

    @AfterEach
    void closeTheThread() {
        release.countDown();
        production.stop();
    }

    private GlSnapshots snapshots() {
        return new GlSnapshots(() -> {
            opened.incrementAndGet();
            openedOn.add(Thread.currentThread().getName());
            if (unreachable) {
                throw new SQLException("away", "08006");
            }
            SnapshotSession session = new SnapshotSession();
            sessions.add(session);
            return session.connection();
        }, Duration.ofSeconds(2));
    }

    /** A source that opens nothing until {@link #release}: a pool with no free connection, or a socket the network black-holed. */
    private GlSnapshots blockedSnapshots() {
        return new GlSnapshots(() -> {
            opened.incrementAndGet();
            openedOn.add(Thread.currentThread().getName());
            await(release);
            return new SnapshotSession().connection();
        }, Duration.ofSeconds(2));
    }

    private AccountingGaugeRead<Long> inLineRead() {
        return new AccountingGaugeRead<>("a test's reading", snapshots(), inLine, clock, FLOOR, connection -> value.get());
    }

    // ------------------------------------------------------------------ off the scrape

    @Test
    @DisplayName("with the connection source blocked, current() returns at once with no value, the read waiting on the refresher's thread and never the caller's")
    void aBlockedConnectionSourceNeverBlocksTheCaller() throws Exception {
        AccountingGaugeRead<Long> read = new AccountingGaugeRead<>("a blocked reading", blockedSnapshots(), production, clock, FLOOR, connection -> 7L);
        ExecutorService scrape = Executors.newSingleThreadExecutor();
        try {
            Future<Long> first = scrape.submit(read::current);
            assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("no reading yet: no value, at once, the source still blocked").isNull();
            awaitOpened(1);
            assertThat(openedOn).as("the read is the refresher's").containsExactly(AccountingGaugeRefresher.THREAD);
            assertThat(scrape.submit(read::current).get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("still blocked, still at once").isNull();
            assertThat(opened).as("no second connection for this gauge").hasValue(1);
            release.countDown();
            awaitReading(read, 7L);
        } finally {
            release.countDown();
            scrape.shutdownNow();
        }
    }

    @Test
    @DisplayName("many gauges, one thread: however many ask at once, one connection is held; each answers at once")
    void manyGaugesOneConnection() throws Exception {
        List<AccountingGaugeRead<Long>> gauges = new ArrayList<>();
        for (int gauge = 0; gauge < 9; gauge++) {
            gauges.add(new AccountingGaugeRead<>("gauge " + gauge, blockedSnapshots(), production, clock, FLOOR, connection -> 7L));
        }
        ExecutorService scrapes = Executors.newFixedThreadPool(10);
        try {
            List<Future<Long>> answers = new ArrayList<>();
            for (int scrape = 0; scrape < 10; scrape++) {
                int which = scrape % gauges.size();
                answers.add(scrapes.submit(gauges.get(which)::current));
            }
            for (Future<Long> answer : answers) {
                assertThat(answer.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNull();
            }
            awaitOpened(1);
            assertThat(opened).as("the one thread holds the one connection; the other reads wait in its queue").hasValue(1);
        } finally {
            release.countDown();
            scrapes.shutdownNow();
        }
    }

    @Test
    @DisplayName("a read that never returns holds the one thread: every gauge on it goes to no value after four of its floors, never its last number; that is the signal, and only a restart ends it")
    void aHungReadTakesEveryGaugeToNoValue() throws Exception {
        CountDownLatch inTheRead = new CountDownLatch(1);
        AccountingGaugeRead<Long> hanging = new AccountingGaugeRead<>("a hanging reading", snapshots(), production, clock, FLOOR, hangsOnItsSecondRead(inTheRead));
        AccountingGaugeRead<Long> other = new AccountingGaugeRead<>("another reading", snapshots(), production, clock, FLOOR, connection -> 8L);
        hanging.current();
        awaitReading(hanging, 7L);
        other.current();
        awaitReading(other, 8L);

        clock.advance(FLOOR);
        assertThat(hanging.current()).as("past its floor: it asks, and the last reading stands").isEqualTo(7);
        assertThat(inTheRead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the read is in, on the one thread").isTrue();
        assertThat(other.current()).as("queued behind it").isEqualTo(8);

        clock.advance(FLOOR.multipliedBy(AccountingGaugeRead.STANDS_FOR_FLOORS).minus(FLOOR).minusMillis(1));
        assertThat(hanging.current()).as("a millisecond inside four floors").isEqualTo(7);
        assertThat(other.current()).isEqualTo(8);
        clock.advance(Duration.ofMillis(1));
        assertThat(hanging.current()).as("four floors on: it can no longer stand").isNull();
        assertThat(other.current()).as("and neither can a gauge that was never slow: nothing is being read").isNull();

        release.countDown();
        awaitReading(other, 8L);
    }

    @Test
    @DisplayName("closed with the context: the thread ends, a read in flight is interrupted, and a gauge read afterwards answers what may stand, then no value, and never throws")
    void closedWithTheContext() throws Exception {
        AccountingGaugeRead<Long> read = new AccountingGaugeRead<>("a closing reading", snapshots(), production, clock, FLOOR, connection -> value.get());
        read.current();
        awaitReading(read, 7L);
        production.start();
        assertThat(production.isRunning()).isTrue();
        production.stop();
        assertThat(production.isRunning()).isFalse();
        clock.advance(FLOOR);
        assertThat(read.current()).as("past its floor, the last reading stands").isEqualTo(7);
        clock.advance(FLOOR.multipliedBy(AccountingGaugeRead.STANDS_FOR_FLOORS));
        assertThat(read.current()).isNull();
        assertThat(Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals(AccountingGaugeRefresher.THREAD)).allMatch(Thread::isDaemon))
                .as("named and daemon, so it never holds a JVM up").isTrue();
    }

    @Test
    @DisplayName("the context's start asks every gauge for its first read, so a healthy instance's first scrape holds numbers: a NaN at a delta window's edge would blind the rule on growth")
    void theFirstReadsAreAskedForAtStart() throws Exception {
        AccountingGaugeRead<Long> read = new AccountingGaugeRead<>("a first reading", snapshots(), production, clock, FLOOR, connection -> value.get());
        assertThat(opened).as("nothing is read before the start").hasValue(0);
        production.start();
        awaitOpened(1);
        assertThat(openedOn).containsExactly(AccountingGaugeRefresher.THREAD);
        awaitReading(read, 7L);
        assertThat(opened).as("the scrape's look finds it there, inside its floor").hasValue(1);
    }

    // ------------------------------------------------------------------ the reading

    @Test
    @DisplayName("past the floor it reads again, inside it the reading stands and no connection is taken")
    void theFloorHolds() {
        AccountingGaugeRead<Long> read = inLineRead();
        assertThat(read.current()).isEqualTo(7);
        value.set(8);
        clock.advance(FLOOR.minusMillis(1));
        assertThat(read.current()).as("inside the floor").isEqualTo(7);
        assertThat(opened).hasValue(1);
        clock.advance(Duration.ofMillis(1));
        assertThat(read.current()).as("at the floor").isEqualTo(8);
        assertThat(opened).hasValue(2);
    }

    @Test
    @DisplayName("the read is a read-only snapshot armed with the statement timeout, and is rolled back")
    void theReadIsBounded() {
        inLineRead().current();
        assertThat(sessions).singleElement().satisfies(session -> assertThat(session.calls)
                .containsSubsequence("readOnly=true", "verify", "timeout=2000ms", "rollback"));
    }

    @Test
    @DisplayName("a connection refused, and a reading that throws, are no value and never a zero; the flag is put down, so the next floor reads again")
    void aFailureIsNoValueAndNeverSticks() {
        AccountingGaugeRead<Long> read = inLineRead();
        unreachable = true;
        assertThat(read.current()).isNull();
        clock.advance(FLOOR);
        unreachable = false;
        assertThat(read.current()).as("read again past the floor").isEqualTo(7);

        AccountingGaugeRead<Long> throwing = new AccountingGaugeRead<>("a failing reading", snapshots(), inLine, clock, FLOOR, connection -> {
            throw new IllegalStateException("a value read from the books");
        });
        assertThat(throwing.current()).isNull();
    }

    @Test
    @DisplayName("a statement the timeout cancelled is no value too")
    void aCancelledStatementIsNoValue() {
        AccountingGaugeRead<Long> read = new AccountingGaugeRead<>("a cancelled reading", snapshots(), inLine, clock, FLOOR, connection -> {
            throw new com.finapp.accounting.AccountingStorageException("reading a thing (SQLState 57014)");
        });
        assertThat(read.current()).isNull();
    }

    @Test
    @DisplayName("a clock stepped back reads again rather than keeping a reading until time catches up")
    void aClockSteppedBackReadsAgain() {
        AccountingGaugeRead<Long> read = inLineRead();
        assertThat(read.current()).isEqualTo(7);
        value.set(8);
        clock.advance(Duration.ofHours(-3));
        assertThat(read.current()).isEqualTo(8);
    }

    // ------------------------------------------------------------------

    /** Answers 7 at once, then holds its second read on {@link #release}. */
    private Function<Connection, Long> hangsOnItsSecondRead(CountDownLatch inTheRead) {
        return new Function<>() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public Long apply(Connection connection) {
                if (calls.incrementAndGet() == 2) {
                    inTheRead.countDown();
                    await(release);
                }
                return 7L;
            }
        };
    }

    /** A real thread's read ends when it ends: this waits for what it wrote, by asking as a scrape would, on no fixed sleep beyond a short poll. */
    private void awaitReading(AccountingGaugeRead<Long> read, long expected) throws InterruptedException {
        long deadline = WAIT.toMillis() / 10;
        for (long tick = 0; tick < deadline; tick++) {
            Long reading = read.current();
            if (reading != null && reading == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(read.current()).as("the refresher's thread read it").isEqualTo(expected);
    }

    private void awaitOpened(int count) throws InterruptedException {
        long deadline = WAIT.toMillis() / 10;
        for (long tick = 0; tick < deadline && opened.get() < count; tick++) {
            Thread.sleep(10);
        }
        assertThat(opened.get()).as("the connection source was asked").isGreaterThanOrEqualTo(count);
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
