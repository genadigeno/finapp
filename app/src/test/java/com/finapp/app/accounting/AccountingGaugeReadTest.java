package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One accounting gauge's read (`P14-TSK-020`; `P14-TSK-017`'s review, M-9; its own review, I-1): a floor, a statement
 * timeout on a read-only snapshot, and no value on any failure. It reads in line: the thread it reads on is the
 * platform's gauge refresher, never a scrape (`P15-TSK-024`), whose single flight, unanswered-ask bound and restart are
 * {@code GaugeRefresherTest}'s, and whose wiring for these gauges is {@code AccountingGaugeWiringTest}'s.
 * <em>(Until `P15-TSK-024` this suite also held the thread cases here, over the accounting module's own refresher.)</em>
 */
@DisplayName("one accounting gauge's read of the database, bounded (P14-TSK-020)")
class AccountingGaugeReadTest {

    private static final Duration FLOOR = Duration.ofSeconds(15);

    private final MovingClock clock = new MovingClock();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicLong value = new AtomicLong(7);
    private final List<SnapshotSession> sessions = new CopyOnWriteArrayList<>();
    private boolean unreachable;

    private GlSnapshots snapshots() {
        return new GlSnapshots(() -> {
            opened.incrementAndGet();
            if (unreachable) {
                throw new SQLException("away", "08006");
            }
            SnapshotSession session = new SnapshotSession();
            sessions.add(session);
            return session.connection();
        }, Duration.ofSeconds(2));
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
    @DisplayName("a connection refused, and a reading that throws, are no value and never a zero; the next floor reads again")
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
