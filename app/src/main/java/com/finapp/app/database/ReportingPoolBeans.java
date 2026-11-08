package com.finapp.app.database;

import com.finapp.app.security.DatabaseEndpoint;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The reporting pool (`P15-TSK-021`): built from the writer's URL and the writer's driver properties - one database,
 * reached the same way, so the transport guard that judges the writer's connection judges this pool's too - and the
 * reporting role's own credential, which {@code DatabaseCredentialGuard} confines to loopback while it is the marked
 * local default. Absent settings are refused at startup, as the budget's are: a pool nobody sized is a number nobody
 * chose.
 */
@Configuration
class ReportingPoolBeans {

    /** Where the writer's driver properties live - {@code sslmode} among them, when it is not in the URL. */
    static final String WRITER_DRIVER_PROPERTIES = "spring.datasource.hikari.data-source-properties";

    @Bean(destroyMethod = "close")
    ReportingPool reportingPool(Environment environment) {
        return new ReportingPool(
                DatabaseEndpoint.url(environment),
                Binder.get(environment)
                        .bind(WRITER_DRIVER_PROPERTIES, Bindable.mapOf(String.class, String.class))
                        .orElse(Map.of()),
                required(environment, ReportingPool.USERNAME_PROPERTY),
                required(environment, ReportingPool.LOGIN_PROPERTY),
                Integer.parseInt(required(environment, ReportingPool.POOL_SIZE_PROPERTY)));
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Refusing to start: " + key + " is not set, so report generation has no"
                    + " role to read as - and it never falls back to the application's.");
        }
        return value;
    }
}
