package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.JournalCensusWalk;
import com.finapp.accounting.SourceCensusStore;
import com.finapp.accounting.SourceFindingKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census's gauges (`P15-TSK-035`): every kind registered from the start, standing findings per kind, zero when none and when
 * healthy, no value when the census cannot be believed - unhealthy, or unreadable - never a reassuring zero; the lag is the oldest
 * unjudged entry's distance from the database's clock. The read itself against the real tables is {@code SourceCensusDatabaseTest}'s
 * (owed); the rules on these gauges are {@code infra/prometheus/tests/correctness.test.yml}'s, run by promtool.
 */
@DisplayName("the census's gauges: standing findings, and no value when it cannot be believed (P15-TSK-035)")
class SourceCensusMetricsTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2097-04-05T09:00:00Z");
    private static final UUID POSITION = new UUID(1, 2);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SourceCensusStore.Reading reading;
    private boolean unreadable;
    private Optional<Instant> oldest = Optional.empty();
    private final SourceCensusMetrics metrics = new SourceCensusMetrics(new FakeStore(), new FakeWalk(), Duration.ofHours(24), Duration.ofMinutes(15),
            new GlSnapshots(this::open, Duration.ofSeconds(2)), FIXED, registry);

    @Test
    @DisplayName("standing findings per kind, every kind from the start, and zero when none stand")
    void standingPerKind() {
        reading = new SourceCensusStore.Reading(Map.of(SourceFindingKind.UNSOURCED, 3L), true, Optional.of(POSITION), NOW);
        assertThat(gauge(SourceFindingKind.UNSOURCED)).isEqualTo(3);
        assertThat(gauge(SourceFindingKind.MISMATCHED)).isZero();
    }

    @Test
    @DisplayName("no value - never a zero - when the census has failed, has gone stale, or cannot be read")
    void noValueWhenItCannotBeBelieved() {
        reading = new SourceCensusStore.Reading(Map.of(), false, Optional.of(POSITION), NOW);
        assertThat(gauge(SourceFindingKind.UNSOURCED)).as("unhealthy").isNaN();
        assertThat(lag()).isNaN();

        SourceCensusMetrics unreadableMetrics;
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        unreadable = true;
        unreadableMetrics = new SourceCensusMetrics(new FakeStore(), new FakeWalk(), Duration.ofHours(24), Duration.ofMinutes(15),
                new GlSnapshots(this::open, Duration.ofSeconds(2)), FIXED, own);
        assertThat(own.get(SourceCensusMetrics.BREAKS).tag("kind", "UNSOURCED").gauge().value()).as("the read itself failed").isNaN();
        assertThat(unreadableMetrics).isNotNull();
    }

    @Test
    @DisplayName("the lag is the distance from the oldest unjudged entry to the database's clock; zero when nothing waits")
    void theLag() {
        reading = new SourceCensusStore.Reading(Map.of(), true, Optional.of(POSITION), NOW);
        oldest = Optional.of(NOW.minusSeconds(1800));
        assertThat(lag()).isEqualTo(1800);
    }

    @Test
    @DisplayName("nothing unjudged reads as a lag of zero")
    void noLagWhenCaughtUp() {
        reading = new SourceCensusStore.Reading(Map.of(), true, Optional.of(POSITION), NOW);
        assertThat(lag()).isZero();
    }

    private double gauge(SourceFindingKind kind) {
        return registry.get(SourceCensusMetrics.BREAKS).tag("kind", kind.name()).gauge().value();
    }

    private double lag() {
        return registry.get(SourceCensusMetrics.LAG).gauge().value();
    }

    private Connection open() throws SQLException {
        if (unreadable) {
            throw new SQLException("the database is away", "08006");
        }
        return new SnapshotSession().connection();
    }

    private final class FakeStore implements SourceCensusStore {
        @Override
        public Instant fence(Connection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Head> claimHead(Connection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertFindings(Connection connection, List<Finding> findings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advance(Connection connection, UUID position, long entriesJudged) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void touch(Connection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailure(Connection connection, String failureClass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Reading read(Connection connection, Duration standing, Duration staleAfter) {
            if (reading == null) {
                throw new AccountingStorageException("no reading planted");
            }
            return reading;
        }
    }

    private final class FakeWalk implements JournalCensusWalk {
        @Override
        public List<CensusEntry> page(Connection connection, UUID after, Instant mintedBefore, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> oldestAfter(Connection connection, UUID position) {
            return oldest;
        }
    }
}
