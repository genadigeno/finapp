package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.RescreeningObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rescreening leg's meters (`P13-TSK-022` and its second review, MINOR 9 - {@code RiskProofMetrics}' pattern): every
 * outcome registered eagerly, {@code not_onboarded} among them; the backlog <strong>never read on the scrape's
 * thread</strong> - a scrape past the floor starts one reading on the refresher, single-flight, and answers the last
 * figure meanwhile; NaN when the reading fails or the figure grows stale, never a stale count.
 */
@DisplayName("the rescreening meters: eager outcomes, the backlog off the scrape thread, single-flight, NaN when"
        + " unreadable or stale (P13-TSK-022)")
class RescreeningMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger readings = new AtomicInteger();
    private final AtomicLong backlog = new AtomicLong(42);
    private final AtomicReference<RuntimeException> failing = new AtomicReference<>();
    /** The readings handed to the refresher and not yet run - a refresher the suite runs by hand. */
    private final List<Runnable> queued = new ArrayList<>();

    private final RescreeningMetrics metrics = new RescreeningMetrics(() -> {
        readings.incrementAndGet();
        if (failing.get() != null) {
            throw failing.get();
        }
        return backlog.get();
    }, queued::add, clock, registry);

    @Test
    @DisplayName("every outcome is registered before any permit concludes - not_onboarded among them - and registration"
            + " reads nothing")
    void registeredEagerly() {
        for (RescreeningObserver.Conclusion conclusion : RescreeningObserver.Conclusion.values()) {
            assertThat(registry.get(RescreeningMetrics.RESCREENING).tag("outcome", conclusion.tag()).counter().count())
                    .as(conclusion.tag()).isZero();
        }
        assertThat(RescreeningObserver.Conclusion.NOT_ONBOARDED.tag()).isEqualTo("not_onboarded");
        metrics.concluded(RescreeningObserver.Conclusion.NOT_ONBOARDED);
        assertThat(registry.get(RescreeningMetrics.RESCREENING).tag("outcome", "not_onboarded").counter().count())
                .isEqualTo(1.0);
        assertThat(readings).hasValue(0);
    }

    @Test
    @DisplayName("a scrape never reads on its own thread: it starts one reading on the refresher and answers the last"
            + " figure (NaN before the first) - ten scrapes start one; the reading done, the figure is served")
    void theBacklogIsReadOffTheScrapeThread() {
        for (int scrape = 0; scrape < 10; scrape++) {
            assertThat(gauge()).as("nothing read yet: absent").isNaN();
        }
        assertThat(readings).as("not on the scrape thread").hasValue(0);
        assertThat(queued).as("single-flight: one reading started").hasSize(1);
        queued.removeFirst().run();
        assertThat(readings).hasValue(1);
        assertThat(gauge()).isEqualTo(42.0);
        backlog.set(7);
        assertThat(gauge()).as("behind the floor: the last figure, nothing started").isEqualTo(42.0);
        assertThat(queued).isEmpty();
        clock.now = clock.now.plus(RescreeningMetrics.MIN_REFRESH);
        assertThat(gauge()).as("past the floor: the last figure while the next reads").isEqualTo(42.0);
        queued.removeFirst().run();
        assertThat(gauge()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("a failed reading is NaN, never zero; a refresher that never finishes leaves the figure NaN at two floors"
            + " - a stuck instance never reports an old count")
    void unreadableOrStaleIsNaN() {
        failing.set(new IllegalStateException("statement timeout"));
        gauge();
        queued.removeFirst().run();
        assertThat(gauge()).as("unreadable").isNaN();

        failing.set(null);
        clock.now = clock.now.plus(RescreeningMetrics.MIN_REFRESH);
        gauge();
        queued.removeFirst().run();
        assertThat(gauge()).isEqualTo(42.0);
        clock.now = clock.now.plus(RescreeningMetrics.MIN_REFRESH);
        assertThat(gauge()).as("a reading started, never finished: the last figure within two floors").isEqualTo(42.0);
        assertThat(queued).hasSize(1);
        clock.now = clock.now.plus(RescreeningMetrics.MIN_REFRESH);
        assertThat(gauge()).as("stale at two floors").isNaN();
        assertThat(queued).as("still single-flight while the first is stuck").hasSize(1);
        assertThat(RescreeningMetrics.STALE_AFTER).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a refresher that refuses the start releases the flag: the next scrape past the floor starts again")
    void aRefusedStartReleasesTheFlag() {
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        AtomicInteger starts = new AtomicInteger();
        new RescreeningMetrics(() -> 1L, reading -> {
            starts.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("no thread");
        }, clock, own);
        assertThat(own.get(RescreeningMetrics.BACKLOG).gauge().value()).isNaN();
        assertThat(own.get(RescreeningMetrics.BACKLOG).gauge().value()).isNaN();
        assertThat(starts).as("each scrape tried again: the flag released").hasValue(2);
    }

    private double gauge() {
        return registry.get(RescreeningMetrics.BACKLOG).gauge().value();
    }

    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
