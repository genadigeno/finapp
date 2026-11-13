package com.finapp.app.shedding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * What one instance admits, and what it sheds before the handler (`P16-TSK-016`, ADR-0137; {@code DELIVERY_PLAN.md} §Phase 16 §7).
 *
 * <h2>Before the claim, by construction</h2>
 *
 * <p>Every financial effect on this platform, and the idempotency claim that guards it, is made inside a handler: claim, effect and
 * recorded outcome commit together (ADR-0004). This decides in the interceptors in front of the handler ({@link ShedAtTheDoor},
 * {@link ShedOnTheDeadline}), so a shed request never entered its handler: no transaction of its command began, no claim exists, and
 * its retry under the same key is a first attempt. A shed is never decided anywhere else.
 *
 * <h2>Two classes, three rules</h2>
 *
 * <ul>
 *   <li><strong>The pool</strong>, every class: a request is shed while {@code pool-waiters} or more threads are already waiting for a
 *       writer connection. Read before anything of the request borrows one - the door runs before authentication, whose lookups are
 *       themselves borrows - so a slow database queues at most about that many requests behind the pool, and the work that shares it
 *       (the schedules, the correctness monitors among them) waits behind that queue and not behind every request thread. Requests the
 *       door admits in the same instant can each add a waiter, so the queue can pass the threshold by those; the permits below are the
 *       absolute bound for commands.
 *   <li><strong>The permits</strong>, commands (every method that is not {@code GET}, {@code HEAD}, {@code OPTIONS} or {@code TRACE}):
 *       at most {@code command-permits} in flight on the instance, fewer than the pool's connections, so commands alone can never hold
 *       every connection. Past them a command is shed at once: never queued in memory to wait for a permit (the entry's exclusion).
 *   <li><strong>The deadline</strong>, commands, after authentication: a command whose request has used so much of its
 *       {@code deadline} that less than one borrow's wait ({@code spring.datasource.hikari.connection-timeout}, read from the pool) is
 *       left is shed before its claim, since the stages before it - the session's and the key's lookups - have already waited on the
 *       pool that long, and its own transaction would wait again.
 * </ul>
 *
 * <h2>Per instance, and no correctness rests on it</h2>
 *
 * <p>The permits are an instance field counting this instance's own commands; the queue is this instance's pool. Ten instances admit
 * ten times as much, which is right. A request shed here and retried on another instance meets the idempotency claim in PostgreSQL,
 * which alone arbitrates, as it always did. Losing the count on a restart costs nothing. Not a {@code Semaphore}:
 * {@code nothingUsesAProcessLocalLock} refuses one because it reads as coordination ({@code DISTRIBUTED_EXECUTION.md} §3).
 */
public final class LoadShedding {

    /** The two classes the door bounds. */
    public enum Type {
        COMMAND("command"),
        READ("read");

        private final String tag;

        Type(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** What the door did with a request: admitted it, or shed it and why. */
    public enum Outcome {
        ADMITTED("admitted"),
        POOL("pool"),
        PERMITS("permits"),
        DEADLINE("deadline");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** One admitted request: its class, when the door admitted it, and whether it holds a command permit until it completes. */
    public record Admission(Type type, Instant admittedAt, boolean holdsPermit) {

        public Admission {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(admittedAt, "admittedAt must not be null");
        }
    }

    /** The settings, each validated against the pool it bounds. */
    public record Bounds(int poolWaiters, int commandPermits, Duration deadline, Duration retryAfter, Duration retryAfterSpread) {

        public Bounds {
            Objects.requireNonNull(deadline, "deadline must not be null");
            Objects.requireNonNull(retryAfter, "retryAfter must not be null");
            Objects.requireNonNull(retryAfterSpread, "retryAfterSpread must not be null");
            if (poolWaiters < 1) {
                throw new IllegalArgumentException("finapp.shedding.pool-waiters must be at least 1: at 0 every request would be shed");
            }
            if (commandPermits < 1) {
                throw new IllegalArgumentException("finapp.shedding.command-permits must be at least 1: at 0 every command would be shed");
            }
            if (retryAfter.toSeconds() < 1 || retryAfter.toMillis() % 1000 != 0) {
                throw new IllegalArgumentException("finapp.shedding.retry-after is whole seconds, at least one");
            }
            if (retryAfterSpread.isNegative() || retryAfterSpread.toMillis() % 1000 != 0) {
                throw new IllegalArgumentException("finapp.shedding.retry-after-spread is whole seconds, zero or more");
            }
        }
    }

    private final PoolQueue pool;
    private final Bounds bounds;
    private final Clock clock;
    private final RandomGenerator jitter;
    private final SheddingMeters meters;
    private final AtomicInteger commandsInFlight = new AtomicInteger();

    /**
     * @throws IllegalArgumentException if the command permits are not fewer than the pool's connections (commands alone could hold the
     *     whole pool, and the ones past it would queue in it), or the deadline does not exceed one borrow's wait (every command would
     *     be shed)
     */
    public LoadShedding(PoolQueue pool, Bounds bounds, Clock clock, RandomGenerator jitter, SheddingMeters meters) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.bounds = Objects.requireNonNull(bounds, "bounds must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.jitter = Objects.requireNonNull(jitter, "jitter must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        if (bounds.commandPermits() >= pool.size()) {
            throw new IllegalArgumentException("Refusing to start: finapp.shedding.command-permits (" + bounds.commandPermits()
                    + ") must be fewer than the writer pool's " + pool.size() + " connections, or commands alone could hold all of them");
        }
        if (bounds.deadline().compareTo(pool.borrowWait()) <= 0) {
            throw new IllegalArgumentException("Refusing to start: finapp.shedding.deadline (" + bounds.deadline()
                    + ") must exceed the pool's borrow wait (" + pool.borrowWait() + "), or every command would be shed");
        }
    }

    /**
     * The door's decision, before anything of the request borrows a connection.
     *
     * @throws RequestShed the pool's queue is at its threshold, or (a command) every permit is held
     */
    Admission admit(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        if (pool.waiting() >= bounds.poolWaiters()) {
            throw shed(type, Outcome.POOL);
        }
        boolean permit = type == Type.COMMAND;
        if (permit && commandsInFlight.incrementAndGet() > bounds.commandPermits()) {
            commandsInFlight.decrementAndGet();
            throw shed(type, Outcome.PERMITS);
        }
        meters.record(type, Outcome.ADMITTED);
        return new Admission(type, clock.instant(), permit);
    }

    /**
     * After authentication, before the source count and the handler: a command with less than one borrow's wait left of its deadline.
     *
     * @throws RequestShed the command cannot finish within its deadline
     */
    void requireTimeLeft(Admission admission) {
        if (admission.type() != Type.COMMAND) {
            return;
        }
        Duration left = bounds.deadline().minus(Duration.between(admission.admittedAt(), clock.instant()));
        if (left.compareTo(pool.borrowWait()) < 0) {
            throw shed(Type.COMMAND, Outcome.DEADLINE);
        }
    }

    /** Gives back what {@link #admit} took. Called once per admission, by the door, on every way out of the request. */
    void release(Admission admission) {
        if (admission.holdsPermit()) {
            commandsInFlight.decrementAndGet();
        }
    }

    /** The commands this instance has admitted and not yet seen complete. */
    public int commandsInFlight() {
        return commandsInFlight.get();
    }

    public Bounds bounds() {
        return bounds;
    }

    /** The pool the door reads, as it was built. */
    public PoolQueue pool() {
        return pool;
    }

    private RequestShed shed(Type type, Outcome why) {
        meters.record(type, why);
        long base = bounds.retryAfter().toSeconds();
        long spread = bounds.retryAfterSpread().toSeconds();
        return new RequestShed(spread == 0 ? base : base + jitter.nextLong(spread + 1));
    }
}
