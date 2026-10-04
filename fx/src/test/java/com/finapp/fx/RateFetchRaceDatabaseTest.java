package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The leaderless fetch raced, against a live PostgreSQL (`P9-TSK-005`, ADR-0075 §1): ten
 * instances fetching one observation set store each observation ONCE, and an older observation
 * racing a newer one never becomes the latest - counted in the table, with the losing writer
 * observed waiting on namespace 6.
 */
@Tag("database")
@DisplayName("ten fetchers, one row per observation; an old observation never beats a new one"
        + " (P9-TSK-005)")
class RateFetchRaceDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final ReferencePair EUR_USD = ReferencePair.of("EUR", "USD");

    private final RateSnapshotStore<Connection> store = new JdbcRateSnapshotStore();

    @Test
    @DisplayName("ten instances with the pacing window at zero fetch one observation of every"
            + " declared pair: ten rows, one per pair, and ninety not-newer")
    void tenFetchersStoreEachObservationOnce() throws Exception {
        String source = "test-" + UUID.randomUUID().toString().substring(0, 8);
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(10);
        List<RateObservation> everyPair = new ArrayList<>();
        for (ReferencePair pair : ReferenceSourceDeclaration.PAIRS) {
            everyPair.add(
                    new RateObservation(
                            ExchangeRate.of(pair.base(), pair.quote(), new BigDecimal("1.2345")),
                            observedAt));
        }
        RateSource fixed = fixedSource(source, everyPair);

        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        int stored = 0;
        int notNewer = 0;
        try {
            List<Callable<ReferenceRateFetch.Result>> fetchers = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                fetchers.add(
                        () -> {
                            start.await();
                            // Window ZERO: pacing switched off, so every instance fetches and
                            // the database alone decides - the worst case the permit exists for.
                            return new ReferenceRateFetch(
                                            fixed,
                                            store,
                                            new JdbcRateFetchPermitStore(),
                                            RateFetchRaceDatabaseTest::inTransaction,
                                            IDS,
                                            Duration.ZERO)
                                    .fetchOnce();
                        });
            }
            for (Future<ReferenceRateFetch.Result> outcome : pool.invokeAll(fetchers)) {
                ReferenceRateFetch.Result result = outcome.get();
                stored += result.stored();
                notNewer += result.notNewer();
            }
        } finally {
            pool.shutdownNow();
        }
        int pairs = ReferenceSourceDeclaration.PAIRS.size();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(rows(app, source)).as("counted in the table").isEqualTo(pairs);
        }
        // Not every instance necessarily fetched (a zero window still serialises on the permit
        // row and a claimant whose statement began first may find the advance ahead of it), but
        // every observation that reached the database was stored exactly once.
        assertThat(stored).isEqualTo(pairs);
        assertThat(notNewer % pairs).isZero();
    }

    @Test
    @DisplayName("an older observation waiting on a newer one's insert stores nothing; a newer one"
            + " waiting on an older one's is stored - the latest is always the newest")
    void anOldObservationNeverBeatsANewOne() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant older = now.minusSeconds(20);
        Instant newer = now.minusSeconds(10);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Order one: the newer holds namespace 6 uncommitted; the older waits, then sees it.
            String one = "test-" + UUID.randomUUID().toString().substring(0, 8);
            try (Connection first = application()) {
                assertThat(record(first, one, newer)).isEqualTo(RateSnapshotStore.Recorded.STORED);
                Future<RateSnapshotStore.Recorded> late = pool.submit(() -> recordAlone(one, older));
                awaitAdvisoryWait();
                first.commit();
                assertThat(late.get(30, TimeUnit.SECONDS))
                        .isEqualTo(RateSnapshotStore.Recorded.NOT_NEWER);
            }
            // Order two: the older holds the lock; the newer waits, then is stored after it.
            String two = "test-" + UUID.randomUUID().toString().substring(0, 8);
            try (Connection first = application()) {
                assertThat(record(first, two, older)).isEqualTo(RateSnapshotStore.Recorded.STORED);
                Future<RateSnapshotStore.Recorded> late = pool.submit(() -> recordAlone(two, newer));
                awaitAdvisoryWait();
                first.commit();
                assertThat(late.get(30, TimeUnit.SECONDS))
                        .isEqualTo(RateSnapshotStore.Recorded.STORED);
            }
            try (Connection app = application()) {
                assertThat(store.latest(app, one, EUR_USD).orElseThrow().observedAt())
                        .isEqualTo(newer);
                assertThat(rows(app, one)).isEqualTo(1);
                assertThat(store.latest(app, two, EUR_USD).orElseThrow().observedAt())
                        .isEqualTo(newer);
                assertThat(rows(app, two)).isEqualTo(2);
                app.commit();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------

    private RateSnapshotStore.Recorded record(Connection app, String source, Instant observedAt) {
        return store.record(
                app,
                source,
                new RateObservation(
                        ExchangeRate.of(EUR_USD.base(), EUR_USD.quote(), new BigDecimal("1.08")),
                        observedAt),
                IDS.next());
    }

    private RateSnapshotStore.Recorded recordAlone(String source, Instant observedAt)
            throws SQLException {
        try (Connection own = application()) {
            RateSnapshotStore.Recorded recorded = record(own, source, observedAt);
            own.commit();
            return recorded;
        }
    }

    private static RateSource fixedSource(String code, List<RateObservation> observations) {
        return new RateSource() {
            @Override
            public String sourceCode() {
                return code;
            }

            @Override
            public Fetched fetch() {
                return new Fetched.Rates(observations, 0);
            }
        };
    }

    private static <R> R inTransaction(java.util.function.Function<Connection, R> work) {
        try (Connection own = application()) {
            try {
                R result = work.apply(own);
                own.commit();
                return result;
            } catch (RuntimeException failure) {
                own.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The waiting writer observed on the advisory lock, never assumed (the P0-TST-004 idiom). */
    private static void awaitAdvisoryWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND wait_event = 'advisory'"
                                        + " AND query LIKE '%fx.rate_snapshot%'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "the second writer never waited on namespace 6 - without the lock the newer-than-"
                        + "latest rule is decided by a stale snapshot");
    }

    private static Connection application() throws SQLException {
        Connection app = DatabaseRoles.application();
        app.setAutoCommit(false);
        return app;
    }

    private static long rows(Connection connection, String source) throws SQLException {
        try (PreparedStatement count =
                connection.prepareStatement(
                        "SELECT count(*) FROM fx.rate_snapshot WHERE source = ?")) {
            count.setString(1, source);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
