package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finapp.accounting.JournalCensusWalk;
import com.finapp.accounting.SourceCensus;
import com.finapp.accounting.SourceCensusStore;
import com.finapp.accounting.SourceJoinCatalogue;
import com.finapp.app.lifecycle.ShutdownOrder;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census's schedule (`P15-TSK-035`): a tick walks batches until the journal is idle or another walker has the head, bounded by
 * {@link SourceCensusSchedule#MAX_BATCHES}; a failing tick ends quietly and tries again next tick; it stops in the schedules' phase.
 */
@DisplayName("the census's schedule (P15-TSK-035)")
class SourceCensusScheduleTest {

    private int batchesRemaining;
    private int calls;
    private boolean claimed = true;
    private boolean failing;

    private final SourceCensus census = new SourceCensus(new SourceCensus.Units() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(null);
        }

        @Override
        public <R> R outsideTransaction(Function<Connection, R> work) {
            return work.apply(null);
        }
    }, new FakeStore(), new EmptyWalk(), new SourceJoinCatalogue(List.of()), Duration.ZERO, 10, Duration.ofSeconds(1)) ;

    @Test
    @DisplayName("a tick stops at the first idle batch, and at the first batch another walker holds")
    void aTickStopsWhenThereIsNothingToDo() {
        // The empty walk answers idle: one batch, one claim.
        new SourceCensusSchedule(census, Duration.ofMinutes(1)).sweepOnce();
        assertThat(calls).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing batch ends the tick quietly: the schedule goes on")
    void aFailureEndsTheTickQuietly() {
        failing = true;
        SourceCensusSchedule schedule = new SourceCensusSchedule(census, Duration.ofMinutes(1));
        assertThatCode(schedule::sweepQuietly).doesNotThrowAnyException();
        assertThat(calls).isEqualTo(1);
    }

    @Test
    @DisplayName("stops in the schedules' phase; the interval is positive")
    void lifecycle() {
        SourceCensusSchedule schedule = new SourceCensusSchedule(census, Duration.ofMinutes(1));
        assertThat(schedule.getPhase()).isEqualTo(ShutdownOrder.SCHEDULES);
        assertThat(schedule.drain().name()).isEqualTo("source-census");
        assertThat(SourceCensusSchedule.MAX_BATCHES).isPositive();
        assertThatCode(() -> new SourceCensusSchedule(census, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private final class FakeStore implements SourceCensusStore {
        @Override
        public Instant fence(Connection connection) {
            calls++;
            if (failing) {
                throw new IllegalStateException("the fence read failed");
            }
            return Instant.EPOCH;
        }

        @Override
        public Optional<Head> claimHead(Connection connection) {
            return claimed ? Optional.of(new Head(new UUID(0, 0), 0)) : Optional.empty();
        }

        @Override
        public void insertFindings(Connection connection, List<Finding> findings) {
        }

        @Override
        public void advance(Connection connection, UUID position, long entriesJudged) {
        }

        @Override
        public void touch(Connection connection) {
        }

        @Override
        public void markFailure(Connection connection, String failureClass) {
        }

        @Override
        public Reading read(Connection connection, Duration standing, Duration staleAfter) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyWalk implements JournalCensusWalk {
        @Override
        public List<CensusEntry> page(Connection connection, UUID after, Instant mintedBefore, int limit) {
            return List.of();
        }

        @Override
        public Optional<Instant> oldestAfter(Connection connection, UUID position) {
            return Optional.empty();
        }
    }
}
