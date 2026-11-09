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
 * The census's gauges (`P15-TSK-035`): every kind registered from the start; a standing finding published even by an unhealthy census, so a
 * transient failure cannot clear a live page; zero only from a healthy one, and no value otherwise; the lag published regardless of health,
 * so a stalled walk can page, and read as the longer of how long the head has stood and how old the waiting entry is. The read itself against
 * the real tables is {@code SourceCensusDatabaseTest}'s (owed); the rules on these gauges are {@code infra/prometheus/tests/correctness.test.yml}'s,
 * run by promtool.
 */
@DisplayName("the census's gauges (P15-TSK-035)")
class SourceCensusMetricsTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2097-04-05T09:00:00Z");
    private static final Optional<JournalCensusWalk.Position> POSITION = Optional.of(new JournalCensusWalk.Position(5, new UUID(1, 2)));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SourceCensusStore.Reading reading;
    private boolean unreadable;
    private Optional<Instant> oldest = Optional.empty();
    private final SourceCensusMetrics metrics = metricsOn(registry);

    private SourceCensusMetrics metricsOn(SimpleMeterRegistry target) {
        return new SourceCensusMetrics(new FakeStore(), new FakeWalk(), Duration.ofHours(24), Duration.ofDays(30), Duration.ofMinutes(15),
                new GlSnapshots(this::open, Duration.ofSeconds(2)), FIXED, target);
    }

    private static SourceCensusStore.Reading healthy(Map<SourceFindingKind, Long> standing, long recent, Instant advancedAt) {
        return new SourceCensusStore.Reading(standing, recent, true, POSITION, NOW, advancedAt);
    }

    @Test
    @DisplayName("standing findings per kind, every kind from the start, and a zero from a healthy census when none stand")
    void standingPerKind() {
        reading = healthy(Map.of(SourceFindingKind.UNSOURCED, 3L), 3, NOW);
        assertThat(gauge(SourceFindingKind.UNSOURCED)).isEqualTo(3);
        assertThat(gauge(SourceFindingKind.MISMATCHED)).isZero();
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("a standing finding is published while the census is unhealthy: a transient failure cannot clear a live page (the review's I1)")
    void aStandingBreakStaysPublishedWhileTheCensusIsUnhealthy() {
        reading = new SourceCensusStore.Reading(Map.of(SourceFindingKind.UNSOURCED, 2L), 2, false, POSITION, NOW, NOW);
        assertThat(gauge(SourceFindingKind.UNSOURCED)).as("unhealthy, yet standing").isEqualTo(2);
        assertThat(gauge(SourceFindingKind.MISMATCHED)).as("unhealthy and nothing standing: no value, never a comforting zero").isNaN();
    }

    @Test
    @DisplayName("the lag is published while the census is unhealthy, so a stalled walk can page; nothing but a failed read makes it NaN")
    void theLagIsPublishedWhileUnhealthy() {
        reading = new SourceCensusStore.Reading(Map.of(), 0, false, POSITION, NOW, NOW.minusSeconds(7200));
        oldest = Optional.of(NOW.minusSeconds(120));
        assertThat(lag()).isEqualTo(7200);
    }

    @Test
    @DisplayName("no value at all when the read itself fails")
    void noValueWhenTheReadFails() {
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        unreadable = true;
        metricsOn(own);
        assertThat(own.get(SourceCensusMetrics.BREAKS).tag("kind", "UNSOURCED").gauge().value()).as("breaks").isNaN();
        assertThat(own.get(SourceCensusMetrics.LAG).gauge().value()).as("lag").isNaN();
        assertThat(own.get(SourceCensusMetrics.FINDINGS).gauge().value()).as("findings").isNaN();
    }

    @Test
    @DisplayName("the ticket's count is the findings of the longer window, the page's having let them go")
    void theRecentCountTicketsPastThePage() {
        reading = healthy(Map.of(), 4, NOW);
        assertThat(registry.get(SourceCensusMetrics.FINDINGS).gauge().value()).isEqualTo(4);
    }

    @Test
    @DisplayName("the lag: the longer of the time since the head moved and the waiting entry's age; a forged future id cannot hide a stalled head")
    void theLagIsTheLonger() {
        reading = healthy(Map.of(), 0, NOW.minusSeconds(4000));
        oldest = Optional.of(NOW.plusSeconds(86_400));
        assertThat(lag()).isEqualTo(4000);
    }

    @Test
    @DisplayName("a backlog that moves still shows its age")
    void aBacklogThatMovesStillShowsItsAge() {
        reading = healthy(Map.of(), 0, NOW.minusSeconds(30));
        oldest = Optional.of(NOW.minusSeconds(1800));
        assertThat(lag()).isEqualTo(1800);
    }

    @Test
    @DisplayName("nothing waiting reads as no lag, however long ago the head moved")
    void noLagWhenNothingWaits() {
        reading = healthy(Map.of(), 0, NOW.minusSeconds(99_999));
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
        public Optional<Head> claimHead(Connection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertFindings(Connection connection, List<Finding> findings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advance(Connection connection, JournalCensusWalk.Position position, long entriesJudged) {
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
        public Reading read(Connection connection, Duration standing, Duration recent, Duration staleAfter) {
            if (reading == null) {
                throw new AccountingStorageException("no reading planted");
            }
            return reading;
        }
    }

    private final class FakeWalk implements JournalCensusWalk {
        @Override
        public List<CensusEntry> page(Connection connection, Position after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> oldestAfter(Connection connection, Position position) {
            return oldest;
        }
    }
}
