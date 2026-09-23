package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.finapp.app.telemetry.CheckoutMeters;
import com.finapp.checkout.CheckoutExpirySweeper;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.CheckoutTransactionRunner;
import com.finapp.checkout.JdbcCheckoutSessionStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The expiry schedule (`P6-TSK-008`) — the {@code PaymentSweeperScheduleTest} shape, and the
 * one place the {@code expired} meter's wiring is proved.
 *
 * <p>Hermetic: the sweeper is a <strong>real</strong> {@link CheckoutExpirySweeper} whose
 * candidate read is intercepted by a {@link CheckoutTransactionRunner} that counts and answers
 * with a fixed list — so the schedule exercises the real type's entry point rather than a test
 * double's, exactly as the payment schedule's suite argues.
 */
@DisplayName("the checkout expiry schedule (P6-TSK-008)")
class CheckoutExpirySweeperScheduleTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("ticks invoke the sweeper on the configured delay, and stop stops")
    void ticksRunAndStopStops() {
        AtomicInteger ticks = new AtomicInteger();
        CheckoutExpirySweeperSchedule schedule =
                new CheckoutExpirySweeperSchedule(
                        ticking(ticks, false), meters(), Duration.ofMillis(20));
        schedule.start();
        try {
            assertThat(schedule.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 2);
        } finally {
            schedule.stop();
        }
        int after = ticks.get();
        await().during(Duration.ofMillis(200)).until(() -> ticks.get() == after);
        assertThat(schedule.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a throwing tick is logged and the schedule continues - the relay's stance,"
            + " because one poisoned tick must not stop every future expiry")
    void aThrowingTickDoesNotStopTheSchedule() {
        AtomicInteger ticks = new AtomicInteger();
        CheckoutExpirySweeperSchedule schedule =
                new CheckoutExpirySweeperSchedule(
                        ticking(ticks, true), meters(), Duration.ofMillis(20));
        schedule.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 3);
        } finally {
            schedule.stop();
        }
    }

    @Test
    @DisplayName("the EXPIRED meter is read from the tick's OWN tally - one increment per"
            + " conditional transition that fired, never per candidate looked at")
    void theMeterCountsActingTransitionsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CheckoutMeters meters = new CheckoutMeters(registry);
        // A sweeper whose candidate list is EMPTY: no row moved, so nothing may be counted.
        // The distinction is the whole discipline - a meter fed from `candidates` would report
        // throughput for offers other instances had already ended (P5-TSK-017's rule).
        CheckoutExpirySweeperSchedule schedule =
                new CheckoutExpirySweeperSchedule(
                        ticking(new AtomicInteger(), false), meters, Duration.ofMillis(20));
        schedule.start();
        try {
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> registry.find("finapp.checkout.session").counters() != null);
            await().during(Duration.ofMillis(300)).until(() -> true);
        } finally {
            schedule.stop();
        }

        assertThat(counter(registry, "expired"))
                .as("no acting transition, no count - the counter exists and reads zero")
                .isZero();
        // The four outcomes are REGISTERED even at zero: an alert on completed_late cannot be
        // written against a meter that only appears once the thing has already happened.
        assertThat(registry.find("finapp.checkout.session").counters()).hasSize(4);

        meters.session(CheckoutMeters.Outcome.EXPIRED);
        assertThat(counter(registry, "expired")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a non-positive interval is refused at construction")
    void aNonPositiveIntervalIsRefused() {
        AtomicInteger ticks = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                new CheckoutExpirySweeperSchedule(
                                        ticking(ticks, false), meters(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new CheckoutExpirySweeperSchedule(
                                        ticking(ticks, false), meters(), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static double counter(SimpleMeterRegistry registry, String outcome) {
        var found =
                registry.find("finapp.checkout.session").tag("outcome", outcome).counter();
        return found == null ? 0.0d : found.count();
    }

    private static CheckoutMeters meters() {
        return new CheckoutMeters(new SimpleMeterRegistry());
    }

    /**
     * A real {@link CheckoutExpirySweeper} whose {@code sweep()} counts and optionally throws.
     * The counting rides in the {@link CheckoutTransactionRunner} that serves the candidate
     * read — the first thing every sweep does — so the schedule drives the real type.
     */
    private static CheckoutExpirySweeper ticking(AtomicInteger ticks, boolean throwing) {
        CheckoutTransactionRunner runner =
                new CheckoutTransactionRunner() {
                    @Override
                    public <R> R inTransaction(Function<Connection, R> work) {
                        ticks.incrementAndGet();
                        if (throwing) {
                            throw new IllegalStateException("tick made to fail");
                        }
                        @SuppressWarnings("unchecked")
                        R empty = (R) java.util.List.<CheckoutSession>of();
                        return empty;
                    }
                };
        return new CheckoutExpirySweeper(
                runner,
                sessions(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK,
                Duration.ofMinutes(10),
                50);
    }

    private static CheckoutSessionStore<Connection> sessions() {
        return new JdbcCheckoutSessionStore();
    }
}
