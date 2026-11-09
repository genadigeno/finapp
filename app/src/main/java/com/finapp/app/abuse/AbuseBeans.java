package com.finapp.app.abuse;

import com.finapp.app.eventing.RetentionSweepMeters;
import com.finapp.app.eventing.RetentionSweepSchedule;
import com.finapp.app.security.ConfinedCredential;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.platform.abuse.SourceBucket;
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
import org.springframework.core.env.Environment;

/** The per-source limits on the public doors, and the sweep of their counter (`P15-TSK-022`). */
@Configuration
class AbuseBeans {

    /** The instance's own pool, one short borrow per count: the fleet's connection budget does not move. */
    @Bean
    WindowCounter windowCounter(
            DataSource dataSource,
            @Value("${finapp.abuse.max-concurrent-counts}") int maxConcurrent,
            @Value("${finapp.abuse.permit-wait}") Duration permitWait) {
        return new WindowCounter(dataSource::getConnection, maxConcurrent, permitWait);
    }

    /** The same key on every replica, or a source counts in a different row per replica. Confined off loopback like every other key. */
    @Bean
    SourceBucket sourceBucket(
            Environment environment,
            @Value("${finapp.abuse.bucket.key:" + ConfinedCredential.MARKED_LOCAL_DEFAULT + "}") String configured) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new SourceBucket(SourceBucketKey.decode(configured, loopback));
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
            @Value("${finapp.abuse.channel-verification.window}") Duration channelVerificationWindow,
            @Value("${finapp.abuse.registration.site-limit}") int registrationSite,
            @Value("${finapp.abuse.sign-in.site-limit}") int signInSite,
            @Value("${finapp.abuse.recovery.site-limit}") int recoverySite,
            @Value("${finapp.abuse.sweep.interval}") Duration sweepInterval,
            @Value("${finapp.abuse.sweep.batch-size}") int sweepBatchSize) {
        return new SourceLimits(
                Map.of(
                        Door.REGISTRATION, new WindowLimit(registration, registrationWindow),
                        Door.AUTHENTICATION, new WindowLimit(signIn, signInWindow),
                        Door.RECOVERY, new WindowLimit(recovery, recoveryWindow),
                        Door.RECOVERY_COMPLETION, new WindowLimit(recoveryCompletion, recoveryCompletionWindow),
                        Door.CHANNEL_VERIFICATION, new WindowLimit(channelVerification, channelVerificationWindow)),
                Map.of(Door.REGISTRATION, registrationSite, Door.AUTHENTICATION, signInSite, Door.RECOVERY, recoverySite),
                sweepBatchSize * RetentionSweepSchedule.MAX_BATCHES * Duration.ofHours(1).toMillis() / sweepInterval.toMillis());
    }

    @Bean
    SourceLimitMeters sourceLimitMeters(MeterRegistry registry) {
        return new SourceLimitMeters(registry);
    }

    @Bean
    SourceLimitInterceptor sourceLimitInterceptor(
            WindowCounter windowCounter, SourceBucket sourceBucket, SourceLimits sourceLimits, SourceLimitMeters meters) {
        return new SourceLimitInterceptor(windowCounter, sourceBucket, sourceLimits, meters);
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
