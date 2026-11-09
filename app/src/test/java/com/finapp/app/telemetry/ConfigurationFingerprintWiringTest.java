package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The fingerprint on the real context (`P15-TSK-031`): one series, one tag, a whole digest. {@code ConfigurationFingerprintTest}
 * holds the arithmetic; this holds that the application's own wiring reaches it, with {@code MetricConventionTest}'s configuration so
 * the same cached context is reused.
 */
@Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("finapp.config.fingerprint is registered by the real wiring (P15-TSK-031)")
class ConfigurationFingerprintWiringTest {

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("the application's own context publishes one series, tagged with a whole digest and with nothing else")
    void theRealContextPublishesIt() {
        Gauge gauge = registry.get(ConfigurationFingerprintBeans.METER).gauge();
        assertThat(gauge.getId().getTags()).hasSize(1);
        assertThat(gauge.getId().getTag(ConfigurationFingerprintBeans.TAG)).matches("[0-9a-f]{64}");
    }
}
