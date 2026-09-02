package com.finapp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The security context, and the two ways it could quietly be wrong.
 *
 * <p>The first is <strong>defaulting</strong>: returning the system actor when none was
 * established. The second is <strong>leaking</strong>: a pooled worker keeping the actor of an
 * earlier flow. Both produce a complete-looking, immutable audit record about the wrong party, so
 * both are asserted here rather than reasoned about.
 */
// The scope handle is deliberately unread: entering it IS the effect, and closing it restores the
// previous actor. That is what try-with-resources is for, so javac's "resource never referenced"
// warning is noise here rather than a signal - and it is emitted whatever the variable is called,
// including "ignored". Same suppression, same reason, as CorrelationContextTest.
@SuppressWarnings("try")
class SecurityContextTest {

    private static final Actor CUSTOMER = new Actor("cust-1", ActorType.CUSTOMER);
    private static final Actor EMPLOYEE = new Actor("emp-7", ActorType.EMPLOYEE);

    @AfterEach
    void leaveNoContextBehind() {
        // These tests share a thread with every other test in the JVM. A leaked actor here would
        // surface as an unrelated suite failing, which is the hardest kind of failure to read.
        assertThat(SecurityContext.current())
                .as("a test must not leave an actor on the shared thread")
                .isEmpty();
    }

    @Test
    @DisplayName("outside any scope there is no actor")
    void absentOutsideAScope() {
        assertThat(SecurityContext.current()).isEmpty();
    }

    @Test
    @DisplayName("require() refuses rather than defaulting to the system actor")
    void requireRefusesOutsideAScope() {
        // THE decision of this class. Returning Actor.SYSTEM here would be convenient, correct
        // today, and a permanent misattribution the day a customer's action runs without a scope.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(SecurityContext::require)
                .withMessageContaining("No actor has been established")
                .withMessageContaining("enterSystem");
    }

    @Test
    @DisplayName("the failure names no actor at all, so it cannot be mistaken for one")
    void theRefusalSuggestsNoActor() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(SecurityContext::require)
                .withMessageNotContaining(Actor.SYSTEM.id());
    }

    @Test
    @DisplayName("a scope makes the actor available and removes it on close")
    void scopeAppliesAndRemoves() {
        try (var scope = SecurityContext.enter(CUSTOMER)) {
            assertThat(SecurityContext.current()).contains(CUSTOMER);
            assertThat(SecurityContext.require()).isEqualTo(CUSTOMER);
        }
        assertThat(SecurityContext.current()).isEmpty();
    }

    @Test
    @DisplayName("a nested scope restores the outer actor rather than clearing it")
    void nestedScopeRestores() {
        // Restore, not clear. An operation running inside another flow must not leave the outer
        // flow anonymous when it finishes - which would make the REST of the outer request
        // unattributable, long after the nested work is forgotten.
        try (var outer = SecurityContext.enter(CUSTOMER)) {
            try (var inner = SecurityContext.enter(EMPLOYEE)) {
                assertThat(SecurityContext.require()).isEqualTo(EMPLOYEE);
            }
            assertThat(SecurityContext.require())
                    .as("the outer actor must survive the inner scope")
                    .isEqualTo(CUSTOMER);
        }
    }

    @Test
    @DisplayName("enterSystem is the platform acting as itself")
    void enterSystemEstablishesTheSystemActor() {
        try (var scope = SecurityContext.enterSystem()) {
            assertThat(SecurityContext.require()).isEqualTo(Actor.SYSTEM);
            assertThat(SecurityContext.require().type()).isEqualTo(ActorType.SYSTEM);
        }
    }

    @Test
    @DisplayName("a propagated task runs under the submitting thread's actor")
    void propagationCarriesTheActor() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Actor> seen = new AtomicReference<>();
            try (var scope = SecurityContext.enter(CUSTOMER)) {
                pool.submit(SecurityContext.propagate(() -> seen.set(SecurityContext.require())))
                        .get(5, TimeUnit.SECONDS);
            }
            assertThat(seen.get()).isEqualTo(CUSTOMER);
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("an unwrapped handoff loses the actor, so the test above is not passing by luck")
    void theNegativeControl() throws Exception {
        // Without this, propagationCarriesTheActor would pass just as happily against an
        // InheritableThreadLocal - the implementation this class exists to avoid.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Boolean> present = new AtomicReference<>();
            try (var scope = SecurityContext.enter(CUSTOMER)) {
                pool.submit(() -> present.set(SecurityContext.current().isPresent()))
                        .get(5, TimeUnit.SECONDS);
            }
            assertThat(present.get())
                    .as("an unwrapped task must NOT see the submitter's actor")
                    .isFalse();
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("a propagated task clears a worker that is already holding a stale actor")
    void noActorLeaksBetweenTasksOnAPooledThread() throws Exception {
        // The worker must be DIRTY before the assertion, and getting that right is the whole
        // difficulty. The first version of this test ran a propagated task and then checked the
        // next one - which proved nothing, because propagate() restores on the way out, so the
        // worker was already clean and the assertion passed whatever apply(null) did. Verified:
        // making apply() ignore an absent actor left that version green.
        //
        // So the thread is dirtied the way a real leak happens - a task that enters a scope and
        // never closes it - and only then is a task submitted from outside any scope. Carrying
        // "no actor" as no actor is what has to clear it. Otherwise the second flow runs, and
        // audits, as whoever the first one was.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> SecurityContext.enter(CUSTOMER)).get(5, TimeUnit.SECONDS);

            AtomicReference<Boolean> dirty = new AtomicReference<>();
            pool.submit(() -> dirty.set(SecurityContext.current().isPresent()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(dirty.get())
                    .as("precondition: the worker really is holding a stale actor")
                    .isTrue();

            AtomicReference<Boolean> present = new AtomicReference<>();
            // Submitted from OUTSIDE any scope, onto that same dirty worker thread.
            pool.submit(
                            SecurityContext.propagate(
                                    () -> present.set(SecurityContext.current().isPresent())))
                    .get(5, TimeUnit.SECONDS);

            assertThat(present.get())
                    .as("the worker must not still be holding the earlier flow's actor")
                    .isFalse();
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("the actor does not escape the thread that established it")
    void scopeIsPerThread() throws Exception {
        CountDownLatch established = new CountDownLatch(1);
        AtomicReference<Boolean> otherThreadSaw = new AtomicReference<>();
        Thread other =
                new Thread(
                        () -> {
                            try {
                                established.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            otherThreadSaw.set(SecurityContext.current().isPresent());
                        });
        other.start();
        try (var scope = SecurityContext.enter(CUSTOMER)) {
            established.countDown();
            other.join(5_000);
        }
        assertThat(otherThreadSaw.get()).isFalse();
    }

    @Test
    @DisplayName("a propagated executor carries the actor onto everything submitted through it")
    void propagatedExecutorCarriesTheActor() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Actor> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            try (var scope = SecurityContext.enter(EMPLOYEE)) {
                SecurityContext.propagate((java.util.concurrent.Executor) pool)
                        .execute(
                                () -> {
                                    seen.set(SecurityContext.require());
                                    done.countDown();
                                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isEqualTo(EMPLOYEE);
        } finally {
            shutdown(pool);
        }
    }

    @Test
    @DisplayName("a propagated Callable returns its value and restores the previous actor")
    void propagatedCallableRestores() throws Exception {
        try (var outer = SecurityContext.enter(EMPLOYEE)) {
            var task = SecurityContext.propagate(() -> SecurityContext.require().id());
            assertThat(task.call()).isEqualTo(EMPLOYEE.id());
            assertThat(SecurityContext.require())
                    .as("running a propagated task must not disturb the caller's own actor")
                    .isEqualTo(EMPLOYEE);
        }
    }

    @Test
    @DisplayName("null is refused at every entry point")
    void nullIsRefused() {
        assertThatNullPointerException().isThrownBy(() -> SecurityContext.enter(null));
        assertThatNullPointerException()
                .isThrownBy(() -> SecurityContext.propagate((Runnable) null));
        assertThatNullPointerException()
                .isThrownBy(() -> SecurityContext.propagate((java.util.concurrent.Callable<?>) null));
        assertThatNullPointerException()
                .isThrownBy(() -> SecurityContext.propagate((java.util.concurrent.Executor) null));
    }

    @Test
    @DisplayName("closing a scope twice is harmless")
    void closingTwiceIsSafe() {
        var scope = SecurityContext.enter(CUSTOMER);
        scope.close();
        assertThatCode(scope::close).doesNotThrowAnyException();
        assertThat(SecurityContext.current()).isEmpty();
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
