package com.finapp.platform.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Correlation must survive the handoffs a real request makes, and must not leak between the
 * unrelated pieces of work that share a pooled thread.
 *
 * <p>Log assertions read what a real Logback appender actually recorded, rather than asserting
 * that a logging method was called. The claim under test is that a log line carries the
 * identifier — a claim about the log, which a mock of the logger cannot make.
 */
// -Xlint:try flags a try-with-resources whose variable is never read. A Scope is used for
// nothing else: entering it is the effect, and closing it restores the previous context. That
// is what try-with-resources is for, so the warning is noise here rather than a signal - and
// javac emits it whatever the variable is called, including "ignored".
@SuppressWarnings("try")
class CorrelationContextTest {

    private static final CorrelationId FLOW = CorrelationId.of("flow-1");
    private static final CorrelationId OTHER_FLOW = CorrelationId.of("flow-2");
    private static final CausationId CAUSE = CausationId.of("cause-1");

    private ListAppender<ILoggingEvent> recorded;
    private Logger logger;

    @BeforeEach
    void startRecordingLogs() {
        logger = (Logger) LoggerFactory.getLogger(CorrelationContextTest.class);
        recorded = new ListAppender<>();
        recorded.start();
        logger.addAppender(recorded);
    }

    @AfterEach
    void stopRecordingAndAssertNoLeak() {
        logger.detachAppender(recorded);
        // Every test must leave the thread as it found it. A test that leaves a context behind
        // would make the next one pass for the wrong reason, which is the very failure this
        // class exists to prevent.
        assertThat(CorrelationContext.current()).as("context leaked out of a test").isEmpty();
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).as("MDC leaked out of a test").isNull();
    }

    // -----------------------------------------------------------------
    // Scoping
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a scope establishes the context and closing it leaves nothing behind")
    void scopeIsEstablishedAndCleanedUp() {
        assertThat(CorrelationContext.current()).isEmpty();

        try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
            assertThat(CorrelationContext.current()).contains(Correlation.startingWith(FLOW));
        }

        assertThat(CorrelationContext.current()).isEmpty();
    }

    @Test
    @DisplayName("a nested scope restores the outer one, rather than clearing it")
    void nestedScopeRestoresTheOuterContext() {
        // A job running inside a request must not wipe the request's context when it ends.
        try (var outer = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
            try (var inner = CorrelationContext.enter(Correlation.startingWith(OTHER_FLOW))) {
                assertThat(currentCorrelationId()).isEqualTo(OTHER_FLOW);
            }
            assertThat(currentCorrelationId()).as("the outer flow must survive the inner one").isEqualTo(FLOW);
            assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo(FLOW.value());
        }
    }

    @Test
    @DisplayName("leaving a caused scope removes the causation, never leaves a stale one")
    void causationDoesNotOutliveItsScope() {
        try (var root = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
            assertThat(MDC.get(CorrelationContext.CAUSATION_ID_KEY)).isNull();

            try (var caused = CorrelationContext.enter(new Correlation(FLOW, CAUSE))) {
                assertThat(MDC.get(CorrelationContext.CAUSATION_ID_KEY)).isEqualTo(CAUSE.value());
            }

            // A root step reporting the previous step's causation would assert a cause that
            // does not exist, which is worse than reporting none.
            assertThat(MDC.get(CorrelationContext.CAUSATION_ID_KEY)).isNull();
        }
    }

    // -----------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------

    @Test
    @DisplayName("log lines written inside a scope carry the correlation identifier")
    void logLinesCarryTheCorrelationId() {
        try (var scope = CorrelationContext.enter(new Correlation(FLOW, CAUSE))) {
            logger.info("inside the flow");
        }
        logger.info("outside the flow");

        List<ILoggingEvent> events = recorded.list;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getMDCPropertyMap())
                .containsEntry(CorrelationContext.CORRELATION_ID_KEY, FLOW.value())
                .containsEntry(CorrelationContext.CAUSATION_ID_KEY, CAUSE.value());
        assertThat(events.get(1).getMDCPropertyMap())
                .as("a line outside the flow must not be attributed to it")
                .doesNotContainKey(CorrelationContext.CORRELATION_ID_KEY);
    }

    // -----------------------------------------------------------------
    // The acceptance criterion: an async handoff
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("propagation across threads")
    class AcrossThreads {

        @Test
        @DisplayName("a wrapped task runs under the submitting thread's context")
        void runnableCarriesTheSubmittersContext() throws Exception {
            AtomicReference<CorrelationId> seen = new AtomicReference<>();

            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                Future<?> done;
                try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                    done = pool.submit(CorrelationContext.propagate(
                            (Runnable) () -> seen.set(currentCorrelationId())));
                }
                done.get(10, TimeUnit.SECONDS);
            }

            assertThat(seen.get()).isEqualTo(FLOW);
        }

        @Test
        @DisplayName("a wrapped callable carries it too, and returns its value")
        void callableCarriesTheSubmittersContext() throws Exception {
            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                Callable<CorrelationId> task = CorrelationContext.propagate(
                        (Callable<CorrelationId>) CorrelationContextTest::currentCorrelationId);

                Future<CorrelationId> result;
                try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                    // Deliberately wrapped *before* entering the scope, then re-wrapped inside:
                    // capture happens at wrap time, so the outer wrap must see nothing.
                    result = pool.submit(CorrelationContext.propagate(
                            (Callable<CorrelationId>) CorrelationContextTest::currentCorrelationId));
                }
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(FLOW);

                Future<CorrelationId> uncorrelated = pool.submit(task);
                assertThat(uncorrelated.get(10, TimeUnit.SECONDS))
                        .as("wrapped outside any scope, so it must carry none")
                        .isNull();
            }
        }

        @Test
        @DisplayName("a decorated executor propagates without every caller remembering to wrap")
        void decoratedExecutorPropagates() throws Exception {
            AtomicReference<CorrelationId> seen = new AtomicReference<>();

            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                var propagating = CorrelationContext.propagate((java.util.concurrent.Executor) pool);

                try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                    propagating.execute(() -> seen.set(currentCorrelationId()));
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(seen.get()).isEqualTo(FLOW);
        }

        @Test
        @DisplayName("a pooled thread does not inherit the previous task's context")
        void pooledThreadDoesNotLeakBetweenTasks() throws Exception {
            // The bug InheritableThreadLocal produces, asserted directly. The second task is
            // unrelated work on the same worker; if it saw FLOW, an investigation would follow
            // that identifier into work it has nothing to do with — confidently wrong
            // correlation, which is worse than none.
            AtomicReference<CorrelationId> secondTaskSaw = new AtomicReference<>(FLOW);

            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                    pool.submit(CorrelationContext.propagate((Runnable) () -> {})).get(10, TimeUnit.SECONDS);
                }

                Future<?> unrelated =
                        pool.submit(CorrelationContext.propagate(
                                (Runnable) () -> secondTaskSaw.set(currentCorrelationId())));
                unrelated.get(10, TimeUnit.SECONDS);
            }

            assertThat(secondTaskSaw.get()).isNull();
        }

        @Test
        @DisplayName("the worker's log context is cleaned up after the task, not left behind")
        void logContextDoesNotLeakOnTheWorkerThread() throws Exception {
            AtomicReference<String> mdcAfterwards = new AtomicReference<>("not run");

            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                    pool.submit(CorrelationContext.propagate((Runnable) () -> {})).get(10, TimeUnit.SECONDS);
                }
                pool.submit(() -> mdcAfterwards.set(MDC.get(CorrelationContext.CORRELATION_ID_KEY)))
                        .get(10, TimeUnit.SECONDS);
            }

            assertThat(mdcAfterwards.get())
                    .as("an unwrapped task on the same worker must not log under the old flow")
                    .isNull();
        }

        @Test
        @DisplayName("context is restored even when the task throws")
        void contextIsRestoredAfterAFailure() {
            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                try (var scope = CorrelationContext.enter(Correlation.startingWith(FLOW))) {
                    Runnable boom =
                            CorrelationContext.propagate(
                                    (Runnable) () -> {
                                        throw new IllegalStateException("task failed");
                                    });
                    assertThatThrownBy(() -> pool.submit(boom).get(10, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class);
                }
            }
            // The submitting thread's own context must be untouched by the worker's failure.
            assertThat(CorrelationContext.current()).isEmpty();
        }
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("rejects a null correlation rather than entering an empty scope")
    void rejectsNulls() {
        assertThatThrownBy(() -> CorrelationContext.enter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorrelationContext.propagate((Runnable) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorrelationContext.propagate((Callable<String>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CorrelationContext.propagate((java.util.concurrent.Executor) null))
                .isInstanceOf(NullPointerException.class);
    }

    private static CorrelationId currentCorrelationId() {
        return CorrelationContext.current().map(Correlation::correlationId).orElse(null);
    }
}
