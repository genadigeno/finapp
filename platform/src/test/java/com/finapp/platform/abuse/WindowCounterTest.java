package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.platform.abuse.WindowCounterUnavailableException.Reason;
import com.finapp.platform.testing.ScriptedWindowConnections;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the counter sends and how it handles its connection, with no database (`P15-TSK-022`). The SQL's behaviour is {@code WindowCounterDatabaseTest}'s. */
@DisplayName("the window counter: one statement, one connection, closed on failure (P15-TSK-022)")
class WindowCounterTest {

    private static final WindowLimit HOURLY_TWO = new WindowLimit(2, Duration.ofHours(1));

    private static final WindowLimit HOURLY_FIVE = new WindowLimit(5, Duration.ofHours(1));

    private static WindowCounter counter(ScriptedWindowConnections database, int maxConcurrent) {
        return new WindowCounter(database::open, maxConcurrent, Duration.ZERO);
    }

    @Test
    @DisplayName("one count is one statement on one connection, its own transaction, returned at once, with a bound on how long it may go unanswered")
    void oneCountIsOneStatement() {
        ScriptedWindowConnections database = new ScriptedWindowConnections();

        counter(database, 2).admit("door.registration", "ab12", HOURLY_TWO);

        ScriptedWindowConnections.Count count = database.counts().get(0);
        assertThat(database.counts()).hasSize(1);
        assertThat(count.scope()).isEqualTo("door.registration");
        assertThat(count.key()).isEqualTo("ab12");
        assertThat(count.limit()).isEqualTo(2);
        assertThat(count.windowSeconds()).isEqualTo(3600);
        assertThat(count.autoCommit()).as("its own transaction: a count rolled back at the connection's return is every request counted as first").isTrue();
        assertThat(count.networkTimeoutMillis()).as("a black-holed read must end").isEqualTo(WindowCounter.NETWORK_TIMEOUT_MILLIS);
        assertThat(count.closed()).as("returned before the handler does anything").isTrue();
        assertThat(database.peakOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("the limit is what the store says: admitted to it, refused past it, with the database's Retry-After")
    void admittedToTheLimitThenRefused() {
        ScriptedWindowConnections database = new ScriptedWindowConnections().retryAfter(77);
        WindowCounter counter = counter(database, 2);

        assertThat(counter.admit("door.x", "k", HOURLY_TWO).admitted()).isTrue();
        assertThat(counter.admit("door.x", "k", HOURLY_TWO).admitted()).isTrue();
        Admission third = counter.admit("door.x", "k", HOURLY_TWO);

        assertThat(third.admitted()).isFalse();
        assertThat(third.retryAfterSeconds()).isEqualTo(77);
    }

    @Test
    @DisplayName("an answer of zero seconds is reported as one, never as 'retry at once'")
    void aZeroRetryIsAtLeastOne() {
        ScriptedWindowConnections database = new ScriptedWindowConnections().retryAfter(0);
        WindowCounter counter = counter(database, 2);

        counter.admit("door.x", "k", new WindowLimit(1, Duration.ofSeconds(1)));
        assertThat(counter.admit("door.x", "k", new WindowLimit(1, Duration.ofSeconds(1))).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a count that cannot be taken is never an admission: it says only the SQLState and a closed reason, not the driver's message")
    void anUnavailableStoreIsNotAnAdmission() {
        ScriptedWindowConnections database = new ScriptedWindowConnections().failingWith("08006");

        assertThatExceptionOfType(WindowCounterUnavailableException.class)
                .isThrownBy(() -> counter(database, 2).admit("door.x", "k", HOURLY_TWO))
                .satisfies(failure -> {
                    assertThat(failure.sqlState()).isEqualTo("08006");
                    assertThat(failure.reason()).isEqualTo(Reason.UNREACHABLE);
                    assertThat(failure.getMessage()).doesNotContain("the store is unavailable");
                });
    }

    @Test
    @DisplayName("a server out of connections and a pool timeout (no SQLState) are the store being unreachable; a refused statement is not")
    void theReasonIsTheFailuresClass() {
        for (String reachability : new String[] {"08001", "53300", "57P01"}) {
            assertThat(reasonFor(reachability)).as(reachability).isEqualTo(Reason.UNREACHABLE);
        }
        assertThat(reasonFor("23514")).isEqualTo(Reason.STATEMENT_FAILED);
        assertThat(reasonFor("42P01")).isEqualTo(Reason.STATEMENT_FAILED);
    }

    private static Reason reasonFor(String sqlState) {
        ScriptedWindowConnections database = new ScriptedWindowConnections().failingWith(sqlState);
        try {
            counter(database, 2).admit("door.x", "k", HOURLY_TWO);
        } catch (WindowCounterUnavailableException failure) {
            return failure.reason();
        }
        throw new AssertionError("no failure");
    }

    @Test
    @DisplayName("at most the cap's counts run at once on an instance; one past it, with no time to wait, is refused at capacity and never reaches the pool")
    void aCountPastTheCapIsRefused() throws Exception {
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedWindowConnections database = new ScriptedWindowConnections().beforeEachCount(() -> {
            inside.countDown();
            await(release);
        });
        WindowCounter counter = counter(database, 2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Admission>> running = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                running.add(threads.submit(() -> counter.admit("door.x", "k", HOURLY_FIVE)));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).as("both counts are in flight").isTrue();
            int opened = database.counts().size();

            assertThatExceptionOfType(WindowCounterUnavailableException.class)
                    .isThrownBy(() -> counter.admit("door.x", "k", HOURLY_FIVE))
                    .satisfies(failure -> assertThat(failure.reason()).isEqualTo(Reason.AT_CAPACITY));

            assertThat(database.counts()).as("the refused count borrowed no connection").hasSize(opened);
            release.countDown();
            for (Future<Admission> one : running) {
                assertThat(one.get(10, TimeUnit.SECONDS).admitted()).isTrue();
            }
            assertThat(counter.admit("door.x", "k", HOURLY_FIVE).admitted()).as("the cap is released with each count").isTrue();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a count past the cap waits for a place, and takes one that comes free in time rather than answering 503")
    void aCountPastTheCapWaitsForAPlace() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedWindowConnections database = new ScriptedWindowConnections().beforeEachCount(() -> {
            inside.countDown();
            await(release);
        });
        WindowCounter counter = new WindowCounter(database::open, 1, Duration.ofSeconds(30));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Admission> holder = threads.submit(() -> counter.admit("door.x", "k", HOURLY_FIVE));
            assertThat(inside.await(10, TimeUnit.SECONDS)).as("the place is taken").isTrue();

            Future<Admission> waiter = threads.submit(() -> counter.admit("door.x", "k", HOURLY_FIVE));
            Thread.sleep(50);
            assertThat(waiter.isDone()).as("it is waiting, not refused").isFalse();
            release.countDown();

            assertThat(waiter.get(20, TimeUnit.SECONDS).admitted()).as("it took the place that came free").isTrue();
            assertThat(holder.get(20, TimeUnit.SECONDS).admitted()).isTrue();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a read the server never answers ends at the network timeout and gives its place back, so a failover cannot close the doors for good")
    void aHungCountGivesItsPlaceBack() throws Exception {
        ScriptedWindowConnections database = new ScriptedWindowConnections().blackHoled();
        WindowCounter counter = counter(database, 1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<WindowCounterUnavailableException> hung = threads.submit(() -> {
                try {
                    counter.admit("door.x", "k", HOURLY_FIVE);
                    return null;
                } catch (WindowCounterUnavailableException failure) {
                    return failure;
                }
            });

            WindowCounterUnavailableException failure = hung.get(10, TimeUnit.SECONDS);
            assertThat(failure).as("the hung read ended and was refused, never admitted").isNotNull();
            assertThat(failure.reason()).isEqualTo(Reason.UNREACHABLE);
            assertThat(database.counts()).allSatisfy(count -> assertThat(count.closed()).isTrue());

            database.healed();
            assertThat(counter.admit("door.x", "k", HOURLY_FIVE).admitted()).as("its place came back").isTrue();
        } finally {
            database.healed();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a scope or key outside the counter's shape is refused before a connection is taken")
    void aMalformedKeyIsRefused() {
        ScriptedWindowConnections database = new ScriptedWindowConnections();
        WindowCounter counter = counter(database, 2);

        assertThatIllegalArgumentException().isThrownBy(() -> counter.admit("Door", "k", HOURLY_TWO));
        assertThatIllegalArgumentException().isThrownBy(() -> counter.admit("door.x", "has space", HOURLY_TWO));
        assertThatIllegalArgumentException().isThrownBy(() -> counter.admit("door.x", "x".repeat(65), HOURLY_TWO));
        assertThat(database.counts()).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
