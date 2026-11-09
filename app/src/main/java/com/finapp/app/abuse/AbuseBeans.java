package com.finapp.app.abuse;

import com.finapp.app.eventing.RetentionSweepMeters;
import com.finapp.app.eventing.RetentionSweepSchedule;
import com.finapp.platform.abuse.WindowCounter;
import com.finapp.platform.abuse.WindowLimit;
import com.finapp.platform.retention.RetentionConnectionSource;
import com.finapp.platform.retention.RetentionSweep;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The per-source limits on the public doors, and the sweep of their counter (`P15-TSK-022`). */
@Configuration
class AbuseBeans {

    /** The instance's own pool, one short borrow per count: the fleet's connection budget does not move. */
    @Bean
    WindowCounter windowCounter(DataSource dataSource, @Value("${finapp.abuse.max-concurrent-counts}") int maxConcurrent) {
        return new WindowCounter(dataSource::getConnection, maxConcurrent);
    }

    @Bean
    SourceLimits sourceLimits(
            @Value("${finapp.abuse.registration.limit}") int registration,
            @Value("${finapp.abuse.registration.window}") Duration registrationWindow,
            @Value("${finapp.abuse.sign-in.limit}") int signIn,
            @Value("${finapp.abuse.sign-in.window}") Duration signInWindow,
            @Value("${finapp.abuse.recovery.limit}") int recovery,
            @Value("${finapp.abuse.recovery.window}") Duration recoveryWindow,
            @Value("${finapp.abuse.recovery-completion.limit}") int recoveryCompletion,
            @Value("${finapp.abuse.recovery-completion.window}") Duration recoveryCompletionWindow,
            @Value("${finapp.abuse.channel-verification.limit}") int channelVerification,
            @Value("${finapp.abuse.channel-verification.window}") Duration channelVerificationWindow) {
        return new SourceLimits(Map.of(
                Door.REGISTRATION, new WindowLimit(registration, registrationWindow),
                Door.AUTHENTICATION, new WindowLimit(signIn, signInWindow),
                Door.RECOVERY, new WindowLimit(recovery, recoveryWindow),
                Door.RECOVERY_COMPLETION, new WindowLimit(recoveryCompletion, recoveryCompletionWindow),
                Door.CHANNEL_VERIFICATION, new WindowLimit(channelVerification, channelVerificationWindow)));
    }

    @Bean
    SourceLimitMeters sourceLimitMeters(MeterRegistry registry) {
        return new SourceLimitMeters(registry);
    }

    @Bean
    SourceLimitInterceptor sourceLimitInterceptor(WindowCounter windowCounter, SourceLimits sourceLimits, SourceLimitMeters meters) {
        return new SourceLimitInterceptor(windowCounter, sourceLimits, meters);
    }

    /** Windows that have ended. {@link RetentionSweep}'s own schedule, for the reasons its other two beans have. */
    @Bean
    @ConditionalOnProperty(name = "finapp.retention.sweep.enabled", havingValue = "true", matchIfMissing = true)
    RetentionSweepSchedule windowCounterSweepSchedule(
            RetentionConnectionSource connections,
            @Value("${finapp.abuse.sweep.grace}") Duration grace,
            @Value("${finapp.abuse.sweep.interval}") Duration interval,
            @Value("${finapp.abuse.sweep.batch-size}") int batchSize,
            RetentionSweepMeters meters) {
        return new RetentionSweepSchedule(
                "window-counter-retention-sweep",
                RetentionSweep.ofExpiredWindowCounters(connections, grace, batchSize),
                interval,
                meters);
    }
}
