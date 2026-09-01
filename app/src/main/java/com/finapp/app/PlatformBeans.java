package com.finapp.app;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The composition root's wiring for the platform's kernel primitives.
 *
 * <p><strong>Why the clock is built here and nowhere else.</strong>
 * {@code NoAmbientTimeRulesTest.onlyTheCompositionRootBuildsASystemClock} fails the build if any
 * module other than {@code app} calls {@code Clock.systemUTC()}. Time is injected everywhere
 * else so that behaviour depending on it can be tested at its boundaries rather than by waiting,
 * and so that a posting date can never be a clock read by accident
 * ({@code DOMAIN_MODEL.md} §Time). Somewhere has to make the real one; this is that place, and
 * it is one line under a rule that keeps it the only one.
 */
@Configuration
public class PlatformBeans {

    /** UTC, not the host's zone: which day an instant falls on must not depend on deployment. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Time-ordered identifiers (ADR-0013).
     *
     * <p>{@code SecureRandom}, and that is not incidental. The generator's 12-bit counter gives
     * ordering <em>within</em> one instance; uniqueness <em>across</em> instances rests entirely
     * on the 62 random bits, so a deterministically seeded generator would have every replica
     * emitting the same identifiers ({@code DISTRIBUTED_EXECUTION.md} §3).
     */
    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return new IdGenerator(clock, new SecureRandom());
    }
}
