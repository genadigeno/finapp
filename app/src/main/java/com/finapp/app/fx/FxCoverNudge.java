package com.finapp.app.fx;

import com.finapp.fx.CoverDispatchNudge;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.platform.security.SecurityContext;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * The booked conversion's post-commit nudge (`P9-TSK-012`; ADR-0077 section 2): sends a freshly
 * born cover NOW, off the customer's request thread, instead of on the next sweep. A hint, never the
 * guarantee - the cover row, born DISPATCHED with its reference and permit, is the guarantee, and
 * the sweep sends whatever a full queue, a crash or a restart dropped. Bounded: a full queue drops
 * the nudge, never blocks or fails the conversion ({@code INV-FX-09}).
 */
@Slf4j
public final class FxCoverNudge implements CoverDispatchNudge, AutoCloseable {

    private final FxCoverDispatch dispatch;
    private final ThreadPoolExecutor executor;

    public FxCoverNudge(FxCoverDispatch dispatch, int threads, int queueCapacity) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("the nudge needs a thread and a queue");
        }
        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "fx-cover-nudge");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void nudge(UUID coverId) {
        try {
            executor.execute(() -> send(coverId));
        } catch (RejectedExecutionException full) {
            // The sweep sends it: dropping a hint loses nothing.
            log.debug("FX cover nudge dropped: the queue is full; the sweep sends it");
        }
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private void send(UUID coverId) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            dispatch.dispatchNow(coverId, SecurityContext.require());
        } catch (RuntimeException failure) {
            // The class name only - a JDBC message can carry values. The sweep retries.
            log.warn("FX cover nudge failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
