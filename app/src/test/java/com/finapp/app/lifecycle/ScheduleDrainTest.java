package com.finapp.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A stopping schedule's drain (`P15-TSK-008`): no new tick starts, the tick in flight finishes within its bound,
 * interrupts it past the bound, returns within the bound and the interrupt's grace whatever the tick does, and - in the
 * form Spring calls - waits on a thread of its own, so a phase of schedules costs one bound. The last case is the hermetic
 * half of the entry's counted test: two instances over one leased claim table, the first stopped inside a claimed batch,
 * one effect per key whether the batch drained or its lease lapsed to the second. Its database half is
 * {@code ScheduleDrainDatabaseTest}, owed (C14).
 */
@DisplayName("a stopping schedule drains its tick within a bound (P15-TSK-008)")
class ScheduleDrainTest {

    private static final Duration SHORT = Duration.ofMillis(300);
    private static final Duration GRACE = Duration.ofMillis(500);

    @Test
    @DisplayName("the tick in flight finishes, uninterrupted, and stop returns only after it; no tick starts once stop began")
    void theTickInFlightFinishes() throws Exception {
        Ticking ticking = new Ticking(new ScheduleDrain("probe", Duration.ofSeconds(10), GRACE));
        ticking.start(Duration.ofMillis(20));
        assertThat(ticking.entered.await(10, TimeUnit.SECONDS)).as("a tick began").isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(ticking.drain::stop);
        awaitStopping(ticking.drain);
        Thread.sleep(200);
        assertThat(stopping).as("stop waits for the tick in flight").isNotDone();
        int ticksAtStop = ticking.ticks.get();

        ticking.release.countDown();
        stopping.get(10, TimeUnit.SECONDS);

        assertThat(ticking.interrupted).as("the tick was let finish, not interrupted").isFalse();
        assertThat(ticking.finished.get()).as("the tick ended before stop returned").isEqualTo(ticksAtStop);
        Thread.sleep(150);
        assertThat(ticking.ticks.get()).as("no tick started once stop began").isEqualTo(ticksAtStop);
        assertThat(ticking.drain.isRunning()).isFalse();
    }

    /** A hold a test turns on and off, telling what it was told to tell. */
    private static final class Hold implements WorkHold {
        private volatile boolean on;
        private final List<Runnable> told = new ArrayList<>();

        private Hold(boolean on) {
            this.on = on;
        }

        @Override
        public boolean held() {
            return on;
        }

        @Override
        public void whenHeld(Runnable onHeld) {
            told.add(onHeld);
        }

        private void comeOn() {
            on = true;
            told.forEach(Runnable::run);
        }
    }

    private static ScheduledExecutorService ticking(AtomicInteger ticks, ScheduleDrain drain) {
        ScheduledExecutorService executor = daemon(drain.name());
        executor.scheduleWithFixedDelay(ticks::incrementAndGet, 20, 20, TimeUnit.MILLISECONDS);
        drain.started(executor);
        return executor;
    }

    private static void awaitTicks(AtomicInteger ticks, int above) throws InterruptedException {
        for (int waited = 0; waited < 200 && ticks.get() <= above; waited++) {
            Thread.sleep(50);
        }
        assertThat(ticks.get()).as("ticks resumed").isGreaterThan(above);
    }

    @Test
    @DisplayName("while the hold is on from the start no tick runs, and once it comes off the schedule ticks")
    void aHeldScheduleRunsNoTick() throws Exception {
        Hold hold = new Hold(true);
        ScheduleDrain drain = new ScheduleDrain("held", Duration.ofSeconds(10), GRACE);
        drain.heldBy(hold);
        AtomicInteger ticks = new AtomicInteger();
        ticking(ticks, drain);
        try {
            Thread.sleep(500);
            assertThat(ticks).as("held from the start").hasValue(0);
            assertThat(drain.isRunning()).as("held is not stopped").isTrue();

            hold.on = false;
            awaitTicks(ticks, 0);
        } finally {
            drain.stop();
        }
    }

    @Test
    @DisplayName("a hold coming on over a running schedule stops its ticks after the one in flight, and coming off resumes them")
    void aHoldComingOnPausesARunningSchedule() throws Exception {
        Hold hold = new Hold(false);
        ScheduleDrain drain = new ScheduleDrain("held", Duration.ofSeconds(10), GRACE);
        drain.heldBy(hold);
        AtomicInteger ticks = new AtomicInteger();
        ticking(ticks, drain);
        try {
            awaitTicks(ticks, 2);

            hold.comeOn();
            Thread.sleep(200);
            int atTheHold = ticks.get();
            Thread.sleep(500);
            assertThat(ticks).as("no tick while held").hasValue(atTheHold);

            hold.on = false;
            awaitTicks(ticks, atTheHold);
        } finally {
            drain.stop();
        }
    }

    @Test
    @DisplayName("a held schedule stops at once, not at its bound: the hold waits for nothing a stop needs")
    void aHeldScheduleStopsPromptly() {
        Hold hold = new Hold(true);
        ScheduleDrain drain = new ScheduleDrain("held", Duration.ofSeconds(30), GRACE);
        drain.heldBy(hold);
        ticking(new AtomicInteger(), drain);

        long started = System.nanoTime();
        drain.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(drain.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a schedule held by nothing runs as it always has, and asking answers no")
    void withNoHoldNothingChanges() throws Exception {
        ScheduleDrain drain = new ScheduleDrain("free", Duration.ofSeconds(10), GRACE);
        AtomicInteger ticks = new AtomicInteger();
        ticking(ticks, drain);
        try {
            awaitTicks(ticks, 0);
            assertThat(drain.held()).isFalse();
        } finally {
            drain.stop();
        }
    }

    @Test
    @DisplayName("past its bound the tick is interrupted, and stop returns within the bound and the grace")
    void pastTheBoundTheTickIsInterrupted() throws Exception {
        Ticking ticking = new Ticking(new ScheduleDrain("probe", SHORT, GRACE));
        ticking.start(Duration.ofMillis(20));
        assertThat(ticking.entered.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        ticking.drain.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(ticking.interrupted).as("the tick that outlived its bound was interrupted").isTrue();
        assertThat(took).as("stop waited out the bound first").isGreaterThanOrEqualTo(SHORT);
        assertThat(took).as("and returned within the bound and the grace").isLessThan(SHORT.plus(GRACE).plusMillis(500));
    }

    @Test
    @DisplayName("a tick deaf to its interrupt is abandoned: stop still returns within its stop bound")
    void aDeafTickIsAbandonedWithinTheBound() throws Exception {
        ScheduleDrain drain = new ScheduleDrain("probe", SHORT, GRACE);
        AtomicBoolean letGo = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        ScheduledExecutorService executor = daemon("probe-deaf");
        executor.execute(() -> {
            entered.countDown();
            while (!letGo.get()) {
                Thread.onSpinWait(); // a socket read: no interrupt reaches it
            }
        });
        drain.started(executor);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            drain.stop();
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertThat(took).isLessThan(drain.stopBound().plusMillis(500));
            assertThat(executor.isTerminated()).as("still running, abandoned to its daemon thread").isFalse();
        } finally {
            letGo.set(true);
        }
    }

    @Test
    @DisplayName("Spring's form ends the ticks on the caller's thread at once, and calls back from another once the tick ends")
    void theCallbackFormStopsClaimingAtOnceAndCallsBackAfterTheDrain() throws Exception {
        Ticking ticking = new Ticking(new ScheduleDrain("probe", Duration.ofSeconds(10), GRACE));
        ticking.start(Duration.ofMillis(20));
        assertThat(ticking.entered.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Thread> calledBack = new CompletableFuture<>();

        ticking.drain.stop(() -> calledBack.complete(Thread.currentThread()));

        assertThat(ticking.drain.isRunning()).as("no new tick once the call returns").isFalse();
        Thread.sleep(200);
        assertThat(calledBack).as("the phase waits while the tick runs").isNotDone();
        ticking.release.countDown();
        Thread caller = calledBack.get(10, TimeUnit.SECONDS);
        assertThat(caller).as("called back off Spring's thread").isNotSameAs(Thread.currentThread());
        assertThat(caller.getName()).isEqualTo("probe-drain");
        assertThat(ticking.interrupted).isFalse();
    }

    @Test
    @DisplayName("a phase of thirty-two schedules, stopped one after another as Spring does, drains in one bound, not thirty-two")
    void aPhaseDrainsInParallel() throws Exception {
        List<Ticking> phase = new ArrayList<>();
        for (int schedule = 0; schedule < 32; schedule++) {
            Ticking ticking = new Ticking(new ScheduleDrain("probe-" + schedule, Duration.ofSeconds(10), GRACE));
            ticking.start(Duration.ofMillis(20));
            phase.add(ticking);
        }
        for (Ticking ticking : phase) {
            assertThat(ticking.entered.await(10, TimeUnit.SECONDS)).isTrue();
        }
        CountDownLatch callbacks = new CountDownLatch(phase.size());
        long started = System.nanoTime();
        for (Ticking ticking : phase) {
            ticking.drain.stop(callbacks::countDown);
        }
        Duration stopping = Duration.ofNanos(System.nanoTime() - started);
        CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS).execute(() -> phase.forEach(t -> t.release.countDown()));

        assertThat(callbacks.await(30, TimeUnit.SECONDS)).isTrue();
        Duration whole = Duration.ofNanos(System.nanoTime() - started);
        assertThat(stopping).as("Spring's loop over the phase returns at once").isLessThan(Duration.ofSeconds(1));
        assertThat(whole).as("one tick's 400 ms, not thirty-two of them").isLessThan(Duration.ofSeconds(3));
        assertThat(phase).allSatisfy(ticking -> assertThat(ticking.interrupted).isFalse());
    }

    @Test
    @DisplayName("stopping is true only from the moment a started schedule's stop begins, while its tick is still running (P15-TSK-009)")
    void stoppingIsTrueFromTheStopOn() throws Exception {
        ScheduleDrain never = new ScheduleDrain("probe");
        assertThat(never.stopping()).as("never started: a suite driving a tick directly").isFalse();
        never.stop();
        assertThat(never.stopping()).as("a stop before any start stops nothing").isFalse();

        Ticking ticking = new Ticking(new ScheduleDrain("probe", Duration.ofSeconds(10), GRACE));
        ticking.start(Duration.ofMillis(20));
        assertThat(ticking.entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ticking.drain.stopping()).as("started and running").isFalse();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(ticking.drain::stop);
        awaitStopping(ticking.drain);
        assertThat(ticking.drain.stopping()).as("the tick is still running, and can ask").isTrue();
        assertThat(stopping).isNotDone();
        ticking.release.countDown();
        stopping.get(10, TimeUnit.SECONDS);
        assertThat(ticking.drain.stopping()).as("and stays so").isTrue();
    }

    @Test
    @DisplayName("a stop before any start is a no-op, and a bound must be positive")
    void edges() {
        ScheduleDrain drain = new ScheduleDrain("probe");
        drain.stop();
        AtomicBoolean called = new AtomicBoolean();
        drain.stop(() -> called.set(true));
        assertThat(called).isTrue();
        assertThat(drain.isRunning()).isFalse();
        assertThat(drain.stopBound()).isEqualTo(ScheduleDrain.BOUND.plus(ScheduleDrain.INTERRUPT_GRACE));
        assertThatThrownBy(() -> new ScheduleDrain("probe", Duration.ZERO, GRACE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleDrain("probe", SHORT, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The hermetic half of the counted test
    // ------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("counted, drained: A stopped inside its claimed batch finishes it, B started after finds nothing, one effect per key")
    void stoppedInsideAClaimedBatchTheBatchFinishes() throws Exception {
        Claims claims = new Claims(keys(8));
        Worker a = new Worker("A", claims, new ScheduleDrain("worker-a", Duration.ofSeconds(10), GRACE));
        a.holdFirst = true;
        a.start();
        assertThat(a.entered.await(10, TimeUnit.SECONDS)).as("A holds a claimed batch").isTrue();
        assertThat(claims.heldBy("A")).as("A claimed the whole batch").hasSize(4);

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(a::stop);
        awaitStopping(a.drain);
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(a.release::countDown);
        stopping.get(10, TimeUnit.SECONDS);

        Worker b = new Worker("B", claims, new ScheduleDrain("worker-b", Duration.ofSeconds(10), GRACE));
        b.start();
        claims.awaitAllDone(Duration.ofSeconds(10));
        b.stop();

        assertThat(claims.effects).as("one effect per key").allSatisfy((key, count) -> assertThat(count.get()).isEqualTo(1));
        assertThat(claims.effects).hasSize(8);
        assertThat(claims.doneBy("A")).as("A's claimed batch finished by A: the drain spared B a retry").hasSize(4);
        assertThat(a.interrupted).isFalse();
    }

    @Test
    @DisplayName("counted, interrupted: A's batch outlives its bound, its leases lapse, B takes them, one effect per key")
    void pastItsBoundTheBatchIsTakenByTheOtherInstance() throws Exception {
        Claims claims = new Claims(keys(8));
        Worker a = new Worker("A", claims, new ScheduleDrain("worker-a", SHORT, GRACE));
        a.holdFirst = true;
        a.start();
        assertThat(a.entered.await(10, TimeUnit.SECONDS)).isTrue();
        List<String> heldByA = claims.heldBy("A");
        assertThat(heldByA).hasSize(4);

        a.stop(); // returns past its bound: A's tick interrupted inside its batch
        assertThat(a.interrupted).isTrue();

        Worker b = new Worker("B", claims, new ScheduleDrain("worker-b", Duration.ofSeconds(10), GRACE));
        b.start();
        claims.awaitDone(4, Duration.ofSeconds(10)); // B's own batch, not A's: A's leases are live
        assertThat(claims.doneBy("B")).as("a live lease is not taken").doesNotContainAnyElementsOf(heldByA);

        claims.lapse(); // the database clock passes A's leases
        claims.awaitAllDone(Duration.ofSeconds(10));
        b.stop();

        assertThat(claims.effects).as("one effect per key").allSatisfy((key, count) -> assertThat(count.get()).isEqualTo(1));
        assertThat(claims.effects).hasSize(8);
        assertThat(claims.doneBy("B")).as("B took A's batch once its leases lapsed").containsAll(heldByA);
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static void awaitStopping(ScheduleDrain drain) throws InterruptedException {
        for (int poll = 0; poll < 1_000 && drain.isRunning(); poll++) {
            Thread.sleep(10);
        }
        assertThat(drain.isRunning()).as("stop began").isFalse();
    }

    private static ScheduledExecutorService daemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>();
        for (int key = 0; key < count; key++) {
            keys.add("key-" + key);
        }
        return keys;
    }

    /** A schedule whose first tick holds until released, counting ticks and whether it was interrupted. */
    private static final class Ticking {
        private final ScheduleDrain drain;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger ticks = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private volatile boolean interrupted;

        private Ticking(ScheduleDrain drain) {
            this.drain = drain;
        }

        private void start(Duration every) {
            ScheduledExecutorService executor = daemon(drain.name());
            executor.scheduleWithFixedDelay(this::tick, 0, every.toMillis(), TimeUnit.MILLISECONDS);
            drain.started(executor);
        }

        private void tick() {
            ticks.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
                finished.incrementAndGet();
            } catch (InterruptedException interruption) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The database's part, in memory: a claim takes a lease, an effect is committed only by the lease's holder while it
     * holds (the fence), and a lapsed lease is claimable again. What is under test is the drain, over this model of what
     * every leased schedule's rows already do.
     */
    private static final class Claims {
        private final Map<String, String> holder = new ConcurrentHashMap<>();
        private final Map<String, String> done = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> effects = new ConcurrentHashMap<>();
        private final AtomicLong epoch = new AtomicLong();
        private final Map<String, Long> leasedAt = new ConcurrentHashMap<>();
        private final List<String> keys;

        private Claims(List<String> keys) {
            this.keys = keys;
        }

        /** Up to four undone keys whose lease is free or lapsed, leased to {@code who}. */
        private List<String> claim(String who) {
            List<String> claimed = new ArrayList<>();
            for (String key : keys) {
                if (claimed.size() == 4) {
                    break;
                }
                if (done.containsKey(key)) {
                    continue;
                }
                AtomicReference<String> took = new AtomicReference<>();
                holder.compute(key, (k, current) -> {
                    boolean free = current == null || leasedAt.get(k) < epoch.get();
                    if (free) {
                        took.set(k);
                        leasedAt.put(k, epoch.get());
                        return who;
                    }
                    return current;
                });
                if (took.get() != null) {
                    claimed.add(key);
                }
            }
            return claimed;
        }

        /** The effect, committed only if {@code who} still holds the key's lease. */
        private void commit(String key, String who) {
            holder.compute(key, (k, current) -> {
                if (who.equals(current) && leasedAt.get(k) == epoch.get() && !done.containsKey(k)) {
                    effects.computeIfAbsent(k, ignored -> new AtomicInteger()).incrementAndGet();
                    done.put(k, who);
                }
                return current;
            });
        }

        private void lapse() {
            epoch.incrementAndGet();
        }

        private List<String> heldBy(String who) {
            return holder.entrySet().stream().filter(entry -> entry.getValue().equals(who)).map(Map.Entry::getKey).sorted().toList();
        }

        private List<String> doneBy(String who) {
            return done.entrySet().stream().filter(entry -> entry.getValue().equals(who)).map(Map.Entry::getKey).sorted().toList();
        }

        private void awaitDone(int count, Duration within) throws InterruptedException {
            for (long waited = 0; done.size() < count && waited < within.toMillis(); waited += 10) {
                Thread.sleep(10);
            }
            assertThat(done).hasSizeGreaterThanOrEqualTo(count);
        }

        private void awaitAllDone(Duration within) throws InterruptedException {
            awaitDone(keys.size(), within);
        }
    }

    /** A leased schedule's shape: each tick claims a batch, then does each key's work and commits its effect. */
    private static final class Worker {
        private final String name;
        private final Claims claims;
        private final ScheduleDrain drain;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean holdFirst;
        private volatile boolean interrupted;

        private Worker(String name, Claims claims, ScheduleDrain drain) {
            this.name = name;
            this.claims = claims;
            this.drain = drain;
        }

        private void start() {
            ScheduledExecutorService executor = daemon(drain.name());
            executor.scheduleWithFixedDelay(this::tick, 0, 20, TimeUnit.MILLISECONDS);
            drain.started(executor);
        }

        private void stop() {
            drain.stop();
        }

        private void tick() {
            for (String key : claims.claim(name)) {
                if (holdFirst) {
                    holdFirst = false;
                    entered.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException interruption) {
                        // The batch is abandoned mid-way: its claims stay leased to this instance until they lapse.
                        interrupted = true;
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                claims.commit(key, name);
            }
        }
    }
}
