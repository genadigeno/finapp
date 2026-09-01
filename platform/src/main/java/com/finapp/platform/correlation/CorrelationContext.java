package com.finapp.platform.correlation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import org.slf4j.MDC;

/**
 * The correlation context of the current thread, and the means of carrying it across threads.
 *
 * <p><strong>Why not {@code InheritableThreadLocal}.</strong> It is the obvious answer and it is
 * wrong for every server. An inheritable value is copied when a thread is <em>created</em>, not
 * when work is submitted, so a pooled worker created while handling request A keeps A's
 * correlation identifier for the rest of its life and stamps it onto every later request it
 * serves. The result is not missing correlation — it is <em>confidently wrong</em> correlation,
 * where an investigation follows the identifier to entirely unrelated work. Context is therefore
 * captured explicitly at submission time by {@link #propagate}.
 *
 * <p><strong>Why the MDC is written here and not by the caller.</strong> Correlation reaches log
 * lines through SLF4J's mapped diagnostic context, and a log context that is set but never
 * cleared is the same pooled-thread bug in a different place: the next task on that thread logs
 * under the previous flow's identifier. Entering and leaving a scope are therefore a matched
 * pair, and leaving restores exactly what was there before rather than clearing — a job running
 * inside a request must not wipe the request's context when it finishes.
 *
 * <p><strong>Scope, honestly.</strong> This is the correlation kernel and its propagation. The
 * HTTP ingress filter that establishes a context from a request header is not here: there is no
 * HTTP surface in the platform yet ({@code P0-EPIC-08}), and the outbox, audit store and tracing
 * exporter that a context must also reach are {@code P0-EPIC-06}, {@code -07} and {@code -09}.
 * Each of those is a five-line call onto this API. See {@code CURRENT_STATE.md} for what that
 * means for this task's acceptance criteria.
 *
 * <p>All state is per-thread; instances are never shared.
 */
public final class CorrelationContext {

    /** MDC keys. Stable strings, because log queries and dashboards are written against them. */
    public static final String CORRELATION_ID_KEY = "correlationId";

    public static final String CAUSATION_ID_KEY = "causationId";

    private static final ThreadLocal<Correlation> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
        // Static holder for per-thread state; not instantiable.
    }

    /** The context of the current thread, or empty outside any scope. */
    public static Optional<Correlation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Enters a scope, restoring the previous context when the returned handle is closed.
     *
     * <p>Intended for try-with-resources:
     *
     * <pre>{@code
     * try (var scope = CorrelationContext.enter(correlation)) {
     *     ...
     * }
     * }</pre>
     */
    public static Scope enter(Correlation correlation) {
        Objects.requireNonNull(correlation, "correlation must not be null");

        Correlation previous = CURRENT.get();
        CURRENT.set(correlation);
        writeToLogContext(correlation);
        return () -> restore(previous);
    }

    /**
     * Wraps a task so it runs under the context of the thread that submitted it.
     *
     * <p>The capture happens <em>now</em>, on the submitting thread, not when the task later
     * runs. That is the whole point: at run time the worker thread's own context is unrelated,
     * or stale from earlier work.
     */
    public static Runnable propagate(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");

        Correlation captured = CURRENT.get();
        return () -> runWith(captured, task);
    }

    /** As {@link #propagate(Runnable)}, for a task that returns a value. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");

        Correlation captured = CURRENT.get();
        return () -> {
            Correlation previous = CURRENT.get();
            apply(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * Decorates an executor so everything submitted through it carries the submitter's context.
     *
     * <p>Wrapping the executor rather than each task is what stops the discipline from depending
     * on everybody remembering it. A single unwrapped {@code submit} is an invisible hole, and
     * it is invisible precisely because losing correlation does not fail anything.
     *
     * <p><strong>This returns an {@link Executor}, not an {@link java.util.concurrent.ExecutorService}.</strong>
     * A caller who needs {@code submit} and a {@code Future} must wrap the task instead, with
     * {@link #propagate(Runnable)} or {@link #propagate(Callable)}. No {@code ExecutorService}
     * decorator is offered because none is needed yet, and fifteen delegating methods written
     * for an imagined caller is the kind of speculative surface that later has to be maintained
     * whether or not anyone uses it. When Spring arrives, its {@code TaskDecorator} is the
     * idiomatic seam and calls straight onto {@link #propagate(Runnable)}.
     */
    public static Executor propagate(Executor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        return task -> executor.execute(propagate(task));
    }

    private static void runWith(Correlation captured, Runnable task) {
        Correlation previous = CURRENT.get();
        apply(captured);
        try {
            task.run();
        } finally {
            restore(previous);
        }
    }

    /** Applies a captured context, which may legitimately be absent. */
    private static void apply(Correlation correlation) {
        if (correlation == null) {
            // Submitted from outside any scope. The worker must run with a clean context
            // rather than inherit whatever it happened to hold from earlier work.
            clear();
        } else {
            CURRENT.set(correlation);
            writeToLogContext(correlation);
        }
    }

    private static void restore(Correlation previous) {
        if (previous == null) {
            clear();
        } else {
            CURRENT.set(previous);
            writeToLogContext(previous);
        }
    }

    private static void clear() {
        CURRENT.remove();
        MDC.remove(CORRELATION_ID_KEY);
        MDC.remove(CAUSATION_ID_KEY);
    }

    private static void writeToLogContext(Correlation correlation) {
        MDC.put(CORRELATION_ID_KEY, correlation.correlationId().value());
        correlation
                .cause()
                .ifPresentOrElse(
                        cause -> MDC.put(CAUSATION_ID_KEY, cause.value()),
                        // Removed rather than left: a root step showing the previous step's
                        // causation would assert a cause that does not exist.
                        () -> MDC.remove(CAUSATION_ID_KEY));
    }

    /** Closing restores the previous context. Never throws. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
