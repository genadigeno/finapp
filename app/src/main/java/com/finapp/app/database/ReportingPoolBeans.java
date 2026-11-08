package com.finapp.app.database;

import com.finapp.app.security.DatabaseEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The reporting pool (`P15-TSK-021`): built from the writer's URL - one database, so the transport guard that judges
 * that URL judges this pool's too - and the reporting role's own credential, which {@code DatabaseCredentialGuard}
 * confines to loopback while it is the marked local default. Absent settings are refused at startup, as the budget's
 * are: a pool nobody sized is a number nobody chose.
 */
@Configuration
class ReportingPoolBeans {

    @Bean(destroyMethod = "close")
    ReportingPool reportingPool(Environment environment) {
        return new ReportingPool(
                DatabaseEndpoint.url(environment),
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
