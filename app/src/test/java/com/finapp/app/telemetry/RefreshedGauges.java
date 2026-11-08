package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.time.Duration;

/**
 * A suite's look at what the database says now, through the production path (`P15-TSK-024`). Every {@code finapp.}
 * gauge of a context is read on the gauge refresher's thread and a look answers its last read, so a suite that asserts a
 * gauge's value after acting first asks the refresher to read now and waits for it ({@link OffScrapeMeterRegistry#refresh}).
 * Never an in-line mode: a context test then proves the path production runs.
 *
 * <p>Waits as long as the reads take: with a database, milliseconds; with the database absent, as a hermetic context has
 * it, each read waits out the pool's {@code connection-timeout} (5 s) before it fails to no value - which is the point of
 * the suites that look at the unreadable path for real, so they settle one module's gauges, not every one.
 */
public final class RefreshedGauges {

    /** Generous: every reading of a context against an absent database takes about as long as one lost scrape once did (96.7 s). */
    private static final Duration WAIT = Duration.ofMinutes(5);

    private RefreshedGauges() {}

    /** Every {@code finapp.} gauge of the context read now, and waited for. */
    public static void settle(MeterRegistry registry) {
        settle(registry, "finapp.");
    }

    /** The gauges whose name starts with {@code prefix} read now, and waited for. */
    public static void settle(MeterRegistry registry, String prefix) {
        try {
            assertThat(offScrape(registry).refresh(prefix, WAIT)).as("the gauge refresher read %s* within %s", prefix, WAIT).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** The context's off-scrape registry, directly or inside a composite. */
    public static OffScrapeMeterRegistry offScrape(MeterRegistry registry) {
        if (registry instanceof OffScrapeMeterRegistry direct) {
            return direct;
        }
        assertThat(registry).as("the context's registry").isInstanceOf(CompositeMeterRegistry.class);
        return ((CompositeMeterRegistry) registry).getRegistries().stream().filter(OffScrapeMeterRegistry.class::isInstance)
                .map(OffScrapeMeterRegistry.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("the context's registry holds no OffScrapeMeterRegistry"));
    }
}
