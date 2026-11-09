package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.database.SchemaVersionGuard.Verdict;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The guard, hermetic, with a stubbed version read (`P15-TSK-005`). Its real read is {@code SchemaVersionReadDatabaseTest}. */
@DisplayName("the schema-version guard holds back an older schema and only an older one (P15-TSK-005)")
class SchemaVersionGuardTest {

    private static final Map<String, SchemaVersion> AS_EXPECTED = versions("10", "9");

    private final MutableClock clock = new MutableClock();

    private static Map<String, SchemaVersion> versions(String a, String b) {
        return Map.of("a", SchemaVersion.parse(a), "b", SchemaVersion.parse(b));
    }

    private static ExpectedSchemaVersions expected() {
        try {
            return ExpectedSchemaVersions.parse(new StringReader("a=10\nb=9\n"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private SchemaVersionGuard onTheCallersThread(AppliedSchemaVersions applied) {
        return new SchemaVersionGuard(expected(), applied, clock, Runnable::run);
    }

    /** A probe, after the floor: asks, and the stub's read runs before the answer because this executor runs it inline. */
    private Verdict probe(SchemaVersionGuard guard) {
        clock.advance(Duration.ofMinutes(5));
        return guard.verdict();
    }

    @Test
    @DisplayName("on the expected schema it is ready")
    void readyOnTheExpectedSchema() {
        assertThat(probe(onTheCallersThread(() -> AS_EXPECTED))).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("any one schema older than the build expects holds the instance back")
    void outOfServiceOnAnOlderSchema() {
        assertThat(probe(onTheCallersThread(() -> versions("9", "9")))).isEqualTo(Verdict.BEHIND);
        assertThat(probe(onTheCallersThread(() -> versions("10", "8")))).isEqualTo(Verdict.BEHIND);
    }

    @Test
    @DisplayName("a schema that has no history at all is older")
    void aSchemaWithNoHistoryIsOlder() {
        assertThat(probe(onTheCallersThread(() -> Map.of("a", SchemaVersion.parse("10"))))).isEqualTo(Verdict.BEHIND);
        assertThat(probe(onTheCallersThread(Map::of))).isEqualTo(Verdict.BEHIND);
    }

    @Test
    @DisplayName("a NEWER schema stays ready: release N - 1 on release N's schema is what a rollout and a rollback are")
    void readyOnANewerSchema() {
        assertThat(probe(onTheCallersThread(() -> versions("11", "9")))).isEqualTo(Verdict.READY);
        assertThat(probe(onTheCallersThread(() -> versions("100", "100")))).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("versions are compared as versions: 10 is not behind 9, and 9 is behind 10")
    void comparedAsVersions() {
        assertThat(probe(onTheCallersThread(() -> versions("10", "10")))).as("'10' sorts before '9' as text").isEqualTo(Verdict.READY);
        assertThat(probe(onTheCallersThread(() -> versions("9", "10")))).as("'9' sorts after '10' as text").isEqualTo(Verdict.BEHIND);
    }

    @Test
    @DisplayName("before any read has succeeded the instance is not ready: nothing has been established")
    void beforeTheFirstReadNothingIsProven() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SchemaVersionGuard guard = new SchemaVersionGuard(expected(), () -> AS_EXPECTED, clock, deferred::add);
        assertThat(guard.verdict()).isEqualTo(Verdict.UNPROVEN);
    }

    @Test
    @DisplayName("the probe never reads: it answers at once while the read waits on its own thread, and takes the result next time")
    void theProbeNeverReads() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        AtomicInteger reads = new AtomicInteger();
        SchemaVersionGuard guard = new SchemaVersionGuard(expected(), () -> {
            reads.incrementAndGet();
            return AS_EXPECTED;
        }, clock, deferred::add);

        assertThat(guard.verdict()).isEqualTo(Verdict.UNPROVEN);
        assertThat(reads).as("the probe's thread read nothing").hasValue(0);
        assertThat(deferred).as("it asked for one read").hasSize(1);

        deferred.remove().run();
        assertThat(reads).hasValue(1);
        assertThat(guard.verdict()).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("the first read is asked for as the application starts, so a replica on the right schema is ready at its first probe")
    void theFirstReadIsAskedAtStart() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SchemaVersionGuard guard = new SchemaVersionGuard(expected(), () -> AS_EXPECTED, clock, deferred::add);

        new SchemaVersionBeans.FirstRead(false, guard).onApplicationEvent(null);
        assertThat(deferred).as("the test overlay's setting").isEmpty();

        new SchemaVersionBeans.FirstRead(true, guard).onApplicationEvent(null);
        assertThat(deferred).as("production's default").hasSize(1);
    }

    @Test
    @DisplayName("one read at a time: probes arriving while one runs ask for no other")
    void oneReadAtATime() {
        Queue<Runnable> deferred = new ArrayDeque<>();
        SchemaVersionGuard guard = new SchemaVersionGuard(expected(), () -> AS_EXPECTED, clock, deferred::add);
        for (int probe = 0; probe < 20; probe++) {
            clock.advance(Duration.ofMinutes(1));
            guard.verdict();
        }
        assertThat(deferred).hasSize(1);
    }

    @Test
    @DisplayName("a ready verdict stands for its floor; a read is asked again only past it")
    void aReadyVerdictStandsForItsFloor() {
        AtomicInteger reads = new AtomicInteger();
        SchemaVersionGuard guard = onTheCallersThread(() -> {
            reads.incrementAndGet();
            return AS_EXPECTED;
        });
        guard.verdict();
        assertThat(reads).hasValue(1);

        clock.advance(SchemaVersionGuard.STEADY.minusSeconds(1));
        guard.verdict();
        assertThat(reads).as("still within the floor").hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        guard.verdict();
        assertThat(reads).as("at the floor").hasValue(2);
    }

    @Test
    @DisplayName("a verdict that is not ready is read again sooner: every probe asks, so the replica is ready at the second probe after the Job")
    void aBehindVerdictIsReadAgainSooner() {
        AtomicInteger reads = new AtomicInteger();
        SchemaVersionGuard guard = onTheCallersThread(() -> {
            reads.incrementAndGet();
            return reads.get() == 1 ? versions("9", "9") : AS_EXPECTED;
        });
        assertThat(guard.verdict()).isEqualTo(Verdict.BEHIND);
        clock.advance(SchemaVersionGuard.UNSETTLED.minusMillis(1));
        assertThat(guard.verdict()).as("not again yet").isEqualTo(Verdict.BEHIND);
        clock.advance(Duration.ofMillis(1));
        assertThat(guard.verdict()).as("the migration Job has finished").isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("the unsettled floor is below the period of the readiness probe the manifest really states")
    @SuppressWarnings("unchecked")
    void theUnsettledFloorIsBelowTheRealProbePeriod() {
        Map<String, Object> deployment = new Yaml(new SafeConstructor(new LoaderOptions())).load(RepositoryPaths.read("deploy/base/deployment.yaml"));
        Map<String, Object> podSpec = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) deployment.get("spec")).get("template")).get("spec");
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).getFirst();
        int period = (Integer) ((Map<String, Object>) container.get("readinessProbe")).get("periodSeconds");

        assertThat(SchemaVersionGuard.UNSETTLED)
                .as("below the period, so every probe of a replica not yet ready asks for a read")
                .isLessThan(Duration.ofSeconds(period));
    }

    @Test
    @DisplayName("a database that does not answer leaves a ready verdict standing: a schema does not get older by being unreachable")
    void anUnreachableDatabaseLeavesAReadyVerdictStanding() {
        Scripted applied = new Scripted(AS_EXPECTED, new SQLTransientConnectionException("the pool timed out"), new SQLException("gone", "08006"),
                new SQLException("no state"));
        SchemaVersionGuard guard = onTheCallersThread(applied);
        assertThat(probe(guard)).isEqualTo(Verdict.READY);
        assertThat(probe(guard)).as("a pool timeout").isEqualTo(Verdict.READY);
        assertThat(probe(guard)).as("a connection failure").isEqualTo(Verdict.READY);
        assertThat(probe(guard)).as("a failure with no state").isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("and leaves an unproven or a behind verdict standing as well, so an outage never reads as ready")
    void anUnreachableDatabaseProvesNothing() {
        assertThat(probe(onTheCallersThread(new Scripted(new SQLException("gone", "08006"))))).isEqualTo(Verdict.UNPROVEN);

        Scripted applied = new Scripted(versions("9", "9"), new SQLException("gone", "08006"));
        SchemaVersionGuard guard = onTheCallersThread(applied);
        assertThat(probe(guard)).isEqualTo(Verdict.BEHIND);
        assertThat(probe(guard)).isEqualTo(Verdict.BEHIND);
    }

    @Test
    @DisplayName("on a replica never ready, a refused read proves nothing: the function absent means platform's migration has not run")
    void aRefusedReadIsNotReady() {
        assertThat(probe(onTheCallersThread(new Scripted(new SQLException("no such function", "42883"))))).isEqualTo(Verdict.UNPROVEN);
        assertThat(probe(onTheCallersThread(new Scripted(new SQLException("denied", "42501"))))).isEqualTo(Verdict.UNPROVEN);
    }

    @Test
    @DisplayName("a ready replica stands on a refused read: the same refusal meets every replica at once, and the schema has not moved")
    void aReadyReplicaStandsOnARefusedRead() {
        Scripted applied = new Scripted(AS_EXPECTED, new SQLException("no such function", "42883"), new SQLException("denied", "42501"), versions("9", "9"));
        SchemaVersionGuard guard = onTheCallersThread(applied);
        assertThat(probe(guard)).isEqualTo(Verdict.READY);
        assertThat(probe(guard)).as("42883").isEqualTo(Verdict.READY);
        assertThat(probe(guard)).as("42501").isEqualTo(Verdict.READY);
        assertThat(probe(guard)).as("only a read that shows an older schema moves it").isEqualTo(Verdict.BEHIND);
    }

    @Test
    @DisplayName("a version that does not parse is never guessed at: nothing is proven on a replica never ready, and a ready one stands")
    void anUnparseableVersionProvesNothing() {
        AppliedSchemaVersions garbled = () -> {
            throw new IllegalArgumentException("not a migration version: x");
        };
        assertThat(probe(onTheCallersThread(garbled))).isEqualTo(Verdict.UNPROVEN);

        Scripted first = new Scripted(AS_EXPECTED);
        SchemaVersionGuard guard = onTheCallersThread(() -> first.remaining() > 0 ? first.read() : garbled.read());
        assertThat(probe(guard)).isEqualTo(Verdict.READY);
        assertThat(probe(guard)).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("background work is held until ready has been proven, and while behind")
    void theHoldFollowsTheVerdict() {
        Scripted applied = new Scripted(new SQLException("gone", "08006"), versions("9", "9"), AS_EXPECTED, new SQLException("gone", "08006"), versions("9", "9"));
        SchemaVersionGuard guard = onTheCallersThread(applied);
        clock.advance(Duration.ofMinutes(5));
        assertThat(guard.held()).as("no read has succeeded").isTrue();
        clock.advance(Duration.ofMinutes(5));
        assertThat(guard.held()).as("behind").isTrue();
        clock.advance(Duration.ofMinutes(5));
        assertThat(guard.held()).as("proven").isFalse();
        clock.advance(Duration.ofMinutes(5));
        assertThat(guard.held()).as("a blip does not hold a ready replica").isFalse();
        clock.advance(Duration.ofMinutes(5));
        assertThat(guard.held()).as("a read that shows an older schema does").isTrue();
    }

    @Test
    @DisplayName("work already running is told only when a ready replica is found behind")
    void workIsToldWhenTheHoldComesOn() {
        Scripted applied = new Scripted(versions("9", "9"), AS_EXPECTED, AS_EXPECTED, versions("9", "9"), versions("9", "9"));
        SchemaVersionGuard guard = onTheCallersThread(applied);
        AtomicInteger told = new AtomicInteger();
        guard.whenHeld(told::incrementAndGet);

        assertThat(probe(guard)).as("never ready: nothing is running to tell").isEqualTo(Verdict.BEHIND);
        assertThat(told).hasValue(0);
        probe(guard);
        probe(guard);
        assertThat(told).as("ready, and ready").hasValue(0);
        assertThat(probe(guard)).isEqualTo(Verdict.BEHIND);
        assertThat(told).as("ready to behind").hasValue(1);
        probe(guard);
        assertThat(told).as("still behind: not told twice").hasValue(1);
    }

    @Test
    @DisplayName("an executor that cannot start a read does not leave the guard believing one is running")
    void aReadThatCouldNotStartIsReleased() {
        AtomicInteger attempts = new AtomicInteger();
        SchemaVersionGuard guard = new SchemaVersionGuard(expected(), () -> AS_EXPECTED, clock, read -> {
            attempts.incrementAndGet();
            throw new OutOfMemoryError("unable to create native thread");
        });
        assertThatThrownBy(guard::ask).isInstanceOf(OutOfMemoryError.class);
        assertThatThrownBy(guard::ask).as("asked again, so the flag came down").isInstanceOf(OutOfMemoryError.class);
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("the production executor reads on a daemon thread of its own, never the caller's")
    void theProductionExecutorIsADaemonThread() throws InterruptedException {
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        SchemaVersionGuard.ON_A_DAEMON_THREAD.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isNotSameAs(Thread.currentThread());
        assertThat(ran.get().isDaemon()).isTrue();
        assertThat(ran.get().getName()).isEqualTo(SchemaVersionGuard.THREAD);
    }

    @Test
    @DisplayName("a restore under running replicas is seen: a ready verdict goes behind when the schema does")
    void aRestoredOlderSchemaIsSeen() {
        Scripted applied = new Scripted(AS_EXPECTED, versions("9", "9"));
        SchemaVersionGuard guard = onTheCallersThread(applied);
        assertThat(probe(guard)).isEqualTo(Verdict.READY);
        assertThat(probe(guard)).isEqualTo(Verdict.BEHIND);
    }

    @Test
    @DisplayName("a failed read does not stop the next one")
    void aFailureReleasesTheRead() {
        Scripted applied = new Scripted(new SQLException("gone", "08006"), AS_EXPECTED);
        SchemaVersionGuard guard = onTheCallersThread(applied);
        assertThat(probe(guard)).isEqualTo(Verdict.UNPROVEN);
        assertThat(probe(guard)).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("ten replicas on one schema: each judges it against its own build, so old stay and new wait, and each reads once per floor")
    void tenReplicas() {
        AtomicInteger reads = new AtomicInteger();
        AppliedSchemaVersions shared = () -> {
            reads.incrementAndGet();
            return AS_EXPECTED;
        };
        List<SchemaVersionGuard> oldReleases = new ArrayList<>();
        List<SchemaVersionGuard> newReleases = new ArrayList<>();
        for (int replica = 0; replica < 5; replica++) {
            oldReleases.add(new SchemaVersionGuard(build("a=9\nb=9\n"), shared, clock, Runnable::run));
            newReleases.add(new SchemaVersionGuard(build("a=11\nb=9\n"), shared, clock, Runnable::run));
        }
        for (int minute = 0; minute < 3; minute++) {
            clock.advance(SchemaVersionGuard.STEADY);
            oldReleases.forEach(guard -> assertThat(guard.verdict()).as("N - 1 on a newer schema").isEqualTo(Verdict.READY));
            newReleases.forEach(guard -> assertThat(guard.verdict()).as("N on an older schema").isEqualTo(Verdict.BEHIND));
        }
        assertThat(reads).as("one read per replica per minute, ten replicas, three minutes").hasValue(30);
    }

    private static ExpectedSchemaVersions build(String properties) {
        try {
            return ExpectedSchemaVersions.parse(new StringReader(properties));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers its script in order: a map of versions to return, or an exception to throw. */
    private static final class Scripted implements AppliedSchemaVersions {
        private final Queue<Object> script;

        Scripted(Object... steps) {
            this.script = new ArrayDeque<>(List.of(steps));
        }

        int remaining() {
            return script.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, SchemaVersion> read() throws SQLException {
            Object step = script.remove();
            if (step instanceof SQLException failure) {
                throw failure;
            }
            return (Map<String, SchemaVersion>) step;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-09T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
