package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The reporting pool reaches the database the way the writer does (`P15-TSK-021`; {@code THREAT_MODEL.md} T7).
 *
 * <p>{@code TransportSecurityGuard} judges the writer's URL and the writer's {@code sslmode} driver property, and the
 * reporting pool connects to the same URL. Found at the gate's final run: the pool took the URL and nothing else, so a
 * deployment that set {@code sslmode=verify-full} as a driver property - the form the guard's own startup test uses -
 * would have had a guard satisfied by the writer and a reporting pool reaching the same remote database under the
 * driver's default, which connects unencrypted without a word. Hermetic: the pool is lazy, so building it opens
 * nothing.
 */
@DisplayName("the reporting pool connects with the writer's transport settings (P15-TSK-021)")
class ReportingPoolTransportTest {

    @Test
    @DisplayName("an sslmode set as the writer's driver property is the reporting pool's too")
    void theWritersSslModeIsCarried() {
        MockEnvironment environment = reportingSettings()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/x")
                .withProperty(ReportingPoolBeans.WRITER_DRIVER_PROPERTIES + ".sslmode", "verify-full")
                .withProperty(ReportingPoolBeans.WRITER_DRIVER_PROPERTIES + ".sslrootcert", "/etc/finapp/root.crt");

        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(environment, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())) {
            assertThat(pool.driverProperty("sslmode")).isEqualTo("verify-full");
            assertThat(pool.driverProperty("sslrootcert")).isEqualTo("/etc/finapp/root.crt");
            // Its own settings still hold after the writer's are applied.
            assertThat(pool.driverProperty("ApplicationName")).isEqualTo("finapp");
            assertThat(pool.driverProperty("connectTimeout")).isEqualTo("5");
        }
    }

    @Test
    @DisplayName("with no driver property set, none is invented")
    void nothingIsInvented() {
        MockEnvironment environment =
                reportingSettings().withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/finapp");

        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(environment, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())) {
            assertThat(pool.driverProperty("sslmode")).isNull();
            assertThat(pool.driverProperty("ApplicationName")).isEqualTo("finapp");
        }
    }

    private static MockEnvironment reportingSettings() {
        return new MockEnvironment()
                .withProperty(ReportingPool.USERNAME_PROPERTY, "finapp_reporting")
                .withProperty(ReportingPool.LOGIN_PROPERTY, "supplied-by-the-test")
                .withProperty(ReportingPool.POOL_SIZE_PROPERTY, "1");
    }
}
