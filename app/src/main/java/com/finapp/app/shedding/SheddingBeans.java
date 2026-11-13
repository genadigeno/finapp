package com.finapp.app.shedding;

import com.finapp.app.surface.RequestSurfaces;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shedding door, wired (`P16-TSK-016`, ADR-0137). Its bounds are {@code finapp.shedding.*} in {@code application.yaml}; the pool's
 * size and borrow wait are read from the pool itself ({@link HikariPoolQueue}), and the bounds are refused at start when they do not fit
 * it ({@link LoadShedding}). Registered by {@code ApiVersionConfiguration}: the door first, the deadline after authentication.
 */
@Configuration(proxyBeanMethods = false)
class SheddingBeans {

    @Bean
    SheddingMeters sheddingMeters(MeterRegistry registry) {
        return new SheddingMeters(registry);
    }

    /**
     * The jitter of {@code Retry-After} only, drawn per shed answer so that a crowd shed together comes back spread. A
     * {@code SecureRandom} because it is thread-safe and the scanners need not be argued with; nothing secret depends on it.
     */
    @Bean
    LoadShedding loadShedding(
            DataSource dataSource,
            Clock clock,
            SheddingMeters sheddingMeters,
            @Value("${finapp.shedding.pool-waiters}") int poolWaiters,
            @Value("${finapp.shedding.command-permits}") int commandPermits,
            @Value("${finapp.shedding.deadline}") Duration deadline,
            @Value("${finapp.shedding.retry-after}") Duration retryAfter,
            @Value("${finapp.shedding.retry-after-spread}") Duration retryAfterSpread) {
        return new LoadShedding(
                HikariPoolQueue.of(dataSource),
                new LoadShedding.Bounds(poolWaiters, commandPermits, deadline, retryAfter, retryAfterSpread),
                clock,
                new SecureRandom(),
                sheddingMeters);
    }

    @Bean
    ShedAtTheDoor shedAtTheDoor(LoadShedding loadShedding, RequestSurfaces requestSurfaces) {
        return new ShedAtTheDoor(loadShedding, requestSurfaces);
    }

    @Bean
    ShedOnTheDeadline shedOnTheDeadline(LoadShedding loadShedding) {
        return new ShedOnTheDeadline(loadShedding);
    }
}
