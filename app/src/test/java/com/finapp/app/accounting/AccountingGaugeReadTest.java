package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What holds a gauge's read to a scrape (`P14-TSK-020`; `P14-TSK-017`'s review, M-9): a floor, one read at a time per instance, a statement timeout on a read-only snapshot, and no value, never a zero and never a throw, on any failure. The threads
 * here are real and the waits are latches, so the single flight is met at the moment it matters rather than assumed from a sequence.
 */
@DisplayName("one accounting gauge's read of the database, bounded (P14-TSK-020)")
class AccountingGaugeReadTest {

    private static final Duration FLOOR = Duration.ofSeconds(15);

    private final MovingClock clock = new MovingClock();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicLong value = new AtomicLong(7);
    private final List<SnapshotSession> sessions = new ArrayList<>();
    private boolean unreachable;

    private GlSnapshots snapshots() {
        return new GlSnapshots(() -> {
            opened.incrementAndGet();
            if (unreachable) {
                throw new SQLException("away", "08006");
            }
            SnapshotSession session = new SnapshotSession();
            synchronizedAdd(session);
            return session.connection();
        }, Duration.ofSeconds(2));
    }

    private void synchronizedAdd(SnapshotSession session) {
        // Only ever one thread here at a time, by the rule under test; a list kept for the assertions.
        sessions.add(session);
    }

    private AccountingGaugeRead<Long> read() {
        return new AccountingGaugeRead<>("a test's reading", snapshots(), clock, FLOOR, connection -> value.get());
    }

    @Test
    @DisplayName("past the floor it reads again, inside it the reading stands and no connection is taken")
    void theFloorHolds() {
        AccountingGaugeRead<Long> read = read();
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
        read().current();
        assertThat(sessions).singleElement().satisfies(session -> assertThat(session.calls)
                .containsSubsequence("readOnly=true", "verify", "timeout=2000ms", "rollback"));
    }

    @Test
    @DisplayName("ten threads past the floor at once: one connection, one read; nine answer at once without waiting for it")
    void tenThreadsOneRead() throws Exception {
        CountDownLatch inTheRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AccountingGaugeRead<Long> read = new AccountingGaugeRead<>("a slow reading", snapshots(), clock, FLOOR, connection -> {
            inTheRead.countDown();
            await(release);
            return 7L;
        });
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            Future<Long> reader = pool.submit(read::current);
            assertThat(inTheRead.await(10, TimeUnit.SECONDS)).as("the first thread is inside its read").isTrue();
            List<Future<Long>> others = new ArrayList<>();
            for (int thread = 0; thread < 9; thread++) {
                others.add(pool.submit(read::current));
            }
            for (Future<Long> other : others) {
                assertThat(other.get(10, TimeUnit.SECONDS)).as("no reading yet: no value, and no wait behind the read").isNull();
            }
            assertThat(opened).as("no second connection while one read is in flight").hasValue(1);
            release.countDown();
            assertThat(reader.get(10, TimeUnit.SECONDS)).isEqualTo(7);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("while a read is in flight the last reading stands for four floors, then no value: a read that never returns ends as a gauge that says so, never as its last number")
    void aHungReadEndsInNoValue() throws Exception {
        AccountingGaugeRead<Long> read = read();
        assertThat(read.current()).isEqualTo(7);
        CountDownLatch inTheRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AccountingGaugeRead<Long> hanging = new AccountingGaugeRead<>("a hanging reading", snapshots(), clock, FLOOR, new java.util.function.Function<>() {
            private int calls;

            @Override
            public Long apply(java.sql.Connection connection) {
                if (++calls == 2) {
                    inTheRead.countDown();
                    await(release);
                }
                return 7L;
            }
        });
        assertThat(hanging.current()).isEqualTo(7);
        clock.advance(FLOOR);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Long> hung = pool.submit(hanging::current);
            assertThat(inTheRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(hanging.current()).as("one floor on: the last reading").isEqualTo(7);
            clock.advance(FLOOR.multipliedBy(AccountingGaugeRead.STANDS_FOR_FLOORS).minus(FLOOR).minusMillis(1));
            assertThat(hanging.current()).as("a millisecond inside four floors").isEqualTo(7);
            clock.advance(Duration.ofMillis(1));
            assertThat(hanging.current()).as("four floors on: it can no longer stand").isNull();
            release.countDown();
            hung.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a connection refused, and a reading that throws, are no value and never a zero; the flag is put down, so the next floor reads again")
    void aFailureIsNoValueAndNeverSticks() {
        AccountingGaugeRead<Long> read = read();
        unreachable = true;
        assertThat(read.current()).isNull();
        clock.advance(FLOOR);
        unreachable = false;
        assertThat(read.current()).as("read again past the floor").isEqualTo(7);

        AccountingGaugeRead<Long> throwing = new AccountingGaugeRead<>("a failing reading", snapshots(), clock, FLOOR, connection -> {
            throw new IllegalStateException("a value read from the books");
        });
        assertThat(throwing.current()).isNull();
    }

    @Test
    @DisplayName("a statement the timeout cancelled is no value too")
    void aCancelledStatementIsNoValue() {
        AccountingGaugeRead<Long> read = new AccountingGaugeRead<>("a cancelled reading", snapshots(), clock, FLOOR, connection -> {
            throw new com.finapp.accounting.AccountingStorageException("reading a thing (SQLState 57014)");
        });
        assertThat(read.current()).isNull();
    }

    @Test
    @DisplayName("a clock stepped back reads again rather than keeping a reading until time catches up")
    void aClockSteppedBackReadsAgain() {
        AccountingGaugeRead<Long> read = read();
        assertThat(read.current()).isEqualTo(7);
        value.set(8);
        clock.advance(Duration.ofHours(-3));
        assertThat(read.current()).isEqualTo(8);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
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
