package com.finapp.app.telemetry;

import com.finapp.platform.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * {@code finapp.config.fingerprint}, once per instance (`P15-TSK-031`, ADR-0125 decision 4): an info gauge, always 1, whose one tag
 * is the digest of this replica's effective {@code FINANCIAL} configuration. {@code ConfigFingerprintDisagrees} counts the distinct
 * digests across the fleet, so a value's meaning is never read, a NaN from a stale refresher is never a blind spot, and an instance's
 * label set stays one series for its whole life.
 *
 * <p>Computed once at the bean's construction: a property does not change while a process runs. A build whose register cannot be read
 * fails here, at startup, with no readiness having been answered.
 */
@Configuration(proxyBeanMethods = false)
class ConfigurationFingerprintBeans {

    /** The meter, {@code PHASE_15_PLAN.md} section 11.2. */
    static final String METER = "finapp.config.fingerprint";

    /** The tag holding the digest: see {@link MetricNames#ALLOWED_TAG_KEYS} for why a digest may be one. */
    static final String TAG = "fingerprint";

    @Bean
    Gauge configurationFingerprintGauge(Environment environment, MeterRegistry registry) {
        String fingerprint = ConfigurationFingerprint.of(ConfigurationRegister.embedded(), environment::getProperty);
        return Gauge.builder(METER, () -> 1)
                .description("1, per instance. The tag is a digest of this replica's effective FINANCIAL configuration and never a value of"
                        + " it: replicas holding different configurations publish different digests. Compare across instances, never read"
                        + " the value")
                .tag(TAG, fingerprint)
                .strongReference(true)
                .register(registry);
    }
}
