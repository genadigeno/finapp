package com.finapp.platform.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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

    @Test
    @DisplayName("one count is one statement on one connection, its own transaction, returned at once")
    void oneCountIsOneStatement() {
        ScriptedWindowConnections database = new ScriptedWindowConnections();

        new WindowCounter(database::open, 2).admit("door.registration", "ab12", HOURLY_TWO);

        ScriptedWindowConnections.Count count = database.counts().get(0);
        assertThat(database.counts()).hasSize(1);
        assertThat(count.scope()).isEqualTo("door.registration");
        assertThat(count.key()).isEqualTo("ab12");
        assertThat(count.limit()).isEqualTo(2);
        assertThat(count.windowSeconds()).isEqualTo(3600);
        assertThat(count.autoCommit()).as("its own transaction: a count rolled back at the connection's return is every request counted as first").isTrue();
        assertThat(count.closed()).as("returned before the handler does anything").isTrue();
        assertThat(database.peakOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("the limit is what the store says: admitted to it, refused past it, with the database's Retry-After")
    void admittedToTheLimitThenRefused() {
        ScriptedWindowConnections database = new ScriptedWindowConnections().retryAfter(77);
        WindowCounter counter = new WindowCounter(database::open, 2);

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
        WindowCounter counter = new WindowCounter(database::open, 2);

        counter.admit("door.x", "k", new WindowLimit(1, Duration.ofSeconds(1)));
        assertThat(counter.admit("door.x", "k", new WindowLimit(1, Duration.ofSeconds(1))).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a count that cannot be taken is never an admission: it says only the SQLState, not the driver's message")
    void anUnavailableStoreIsNotAnAdmission() {
        ScriptedWindowConnections database = new ScriptedWindowConnections().failingWith("08006");

        assertThatExceptionOfType(WindowCounterUnavailableException.class)
                .isThrownBy(() -> new WindowCounter(database::open, 2).admit("door.x", "k", HOURLY_TWO))
                .satisfies(failure -> {
                    assertThat(failure.sqlState()).isEqualTo("08006");
                    assertThat(failure.getMessage()).doesNotContain("the store is unavailable");
                });
    }

    @Test
    @DisplayName("at most the cap's counts run at once on an instance; one past it is refused at once and never reaches the pool")
    void aCountPastTheCapIsRefused() throws Exception {
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedWindowConnections database = new ScriptedWindowConnections().beforeEachCount(() -> {
            inside.countDown();
            await(release);
        });
        WindowCounter counter = new WindowCounter(database::open, 2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Admission>> running = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                running.add(threads.submit(() -> counter.admit("door.x", "k", new WindowLimit(5, Duration.ofHours(1)))));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).as("both counts are in flight").isTrue();
            int opened = database.counts().size();

            assertThatExceptionOfType(WindowCounterUnavailableException.class)
                    .isThrownBy(() -> counter.admit("door.x", "k", new WindowLimit(5, Duration.ofHours(1))));

            assertThat(database.counts()).as("the refused count borrowed no connection").hasSize(opened);
            release.countDown();
            for (Future<Admission> one : running) {
                assertThat(one.get(10, TimeUnit.SECONDS).admitted()).isTrue();
            }
            assertThat(counter.admit("door.x", "k", new WindowLimit(5, Duration.ofHours(1))).admitted())
                    .as("the cap is released with each count").isTrue();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a scope or key outside the counter's shape is refused before a connection is taken")
    void aMalformedKeyIsRefused() {
        ScriptedWindowConnections database = new ScriptedWindowConnections();
        WindowCounter counter = new WindowCounter(database::open, 2);

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
