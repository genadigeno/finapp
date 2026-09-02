package com.finapp.platform.security;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * Who is acting in the current flow, and the means of carrying that across threads.
 *
 * <p>The security-context half of ADR-0010's {@code ActorId}/{@code ActorType} abstraction. The
 * value types are {@link Actor} and {@link ActorType}; this is the mechanism that makes an actor
 * available to code that must record one, without every method signature between the entry point
 * and the audit write carrying an {@code Actor} parameter it does not otherwise use.
 *
 * <h2>Absence is an error, never the system actor</h2>
 *
 * <p>{@link #current()} is empty outside a scope and {@link #require} throws. It would be far more
 * convenient to return {@link Actor#SYSTEM} instead, and that convenience is the whole risk.
 *
 * <p>Today the only actor is the system, so a default would be harmless and correct. In Phase 1 it
 * becomes actively dangerous: an authenticated request whose scope was never established - a new
 * entry point, a thread handoff someone forgot to wrap - would silently record the platform as
 * having done what a customer did. Nothing fails. The record looks complete. It is simply about the
 * wrong person, and it is immutable ({@code INV-HIST-03}), so the mistake is permanent and
 * undetectable.
 *
 * <p>{@link Actor}'s own javadoc already states the principle for a blank identifier: an action
 * whose actor was not established is not attributable to the platform, it is an action nobody can
 * answer for, and recording a plausible actor makes the record worse than absent by making it
 * wrong. This class applies the same rule one level up. A missing scope is a wiring defect, and a
 * wiring defect should be loud on the first request rather than silent for a year.
 *
 * <p>So Phase 0 establishes the system actor <strong>explicitly</strong>, through
 * {@link #enterSystem()}. That call is greppable, which is the point: it is the list of places
 * Phase 1 must revisit when real identity arrives.
 *
 * <h2>Threading</h2>
 *
 * <p>Deliberately the same design as {@code CorrelationContext}, including the reason for it: not
 * {@code InheritableThreadLocal}, because an inheritable value is copied when a thread is
 * <em>created</em> rather than when work is submitted, so a pooled worker keeps the actor of
 * whichever request happened to create it and stamps that identity onto everything it later
 * serves. For correlation that misattributes a trace. For an actor it misattributes a financial
 * action to a real person, which is a different order of mistake.
 *
 * <p>The two contexts compose rather than combining, because they are separate concerns with
 * separate lifetimes - a background job has an actor and no inbound correlation:
 *
 * <pre>{@code
 * Executor carrying = SecurityContext.propagate(CorrelationContext.propagate(pool));
 * }</pre>
 *
 * <p>All state is per-thread; instances are never shared.
 */
public final class SecurityContext {

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private SecurityContext() {
        // Static holder for per-thread state; not instantiable.
    }

    /** The actor of the current thread, or empty outside any scope. */
    public static Optional<Actor> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The actor of the current thread.
     *
     * @throws IllegalStateException outside any scope. Callers that must record an actor use this
     *     rather than {@link #current()}, so that a missing scope fails the operation instead of
     *     producing a record attributing it to nobody - or, worse, to a default.
     */
    public static Actor require() {
        Actor actor = CURRENT.get();
        if (actor == null) {
            throw new IllegalStateException(
                    "No actor has been established for this flow. An auditable action must run"
                            + " inside a SecurityContext scope so the record says who performed it"
                            + " (INV-AUD-01). Entry points establish one: a request from the"
                            + " authenticated identity, a job or consumer via"
                            + " SecurityContext.enterSystem().");
        }
        return actor;
    }

    /**
     * Enters a scope, restoring the previous actor when the returned handle is closed.
     *
     * <p>Restores rather than clears, for the same reason {@code CorrelationContext} does: work
     * running inside another flow must not wipe the outer flow's context when it finishes.
     *
     * <pre>{@code
     * try (var scope = SecurityContext.enter(actor)) {
     *     ...
     * }
     * }</pre>
     */
    public static Scope enter(Actor actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        Actor previous = CURRENT.get();
        CURRENT.set(actor);
        return () -> restore(previous);
    }

    /**
     * Enters a scope as the platform itself: a scheduled job, a relay, a consumer.
     *
     * <p>Named rather than {@code enter(Actor.SYSTEM)} so that the places claiming to act as the
     * platform can be found with one search. That matters at exactly one moment - when Phase 1
     * brings real identity and someone has to decide, for each of them, whether it is genuinely
     * platform-initiated work or a request that should now carry a person.
     */
    public static Scope enterSystem() {
        return enter(Actor.SYSTEM);
    }

    /**
     * Wraps a task so it runs under the actor of the thread that submitted it.
     *
     * <p>The capture happens <em>now</em>, on the submitting thread, not when the task later runs:
     * at run time the worker's own context is unrelated, or stale from earlier work.
     */
    public static Runnable propagate(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        Actor captured = CURRENT.get();
        return () -> runWith(captured, task);
    }

    /** As {@link #propagate(Runnable)}, for a task that returns a value. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        Actor captured = CURRENT.get();
        return () -> {
            Actor previous = CURRENT.get();
            apply(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    /** An executor that carries the submitting thread's actor onto every task given to it. */
    public static Executor propagate(Executor delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        return task -> delegate.execute(propagate(task));
    }

    private static void runWith(Actor captured, Runnable task) {
        Actor previous = CURRENT.get();
        apply(captured);
        try {
            task.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * Applies a captured actor, which may legitimately be absent.
     *
     * <p>Absent is applied as absent rather than left alone. A task submitted from outside any
     * scope must not inherit the worker thread's leftover actor - that is the pooled-thread leak
     * this class exists to prevent, and it is the case where inheriting is most tempting and most
     * wrong.
     */
    private static void apply(Actor captured) {
        if (captured == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(captured);
        }
    }

    private static void restore(Actor previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** A scope handle. {@link #close()} does not throw, so try-with-resources stays readable. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
