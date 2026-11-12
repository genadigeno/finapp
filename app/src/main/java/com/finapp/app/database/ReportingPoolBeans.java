package com.finapp.app.database;

import com.finapp.app.security.DatabaseEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p><strong>Never a role that writes</strong> (the review's fix round, 2026-11-08): the pool exists so that report
 * generation cannot write whatever its transaction says. A {@code FINAPP_DB_REPORTING_USER} naming the application's
 * role, the migrator or the superuser would give it back every privilege the pool exists to remove, silently and with
 * every check green - so startup refuses it.
 */
@Configuration
class ReportingPoolBeans {

    /** Where the writer's driver properties live - {@code sslmode} among them, when it is not in the URL. */
    static final String WRITER_DRIVER_PROPERTIES = "spring.datasource.hikari.data-source-properties";

    /**
     * Roles that write, whatever a deployment calls its application role: the migrator and the superuser; and since
     * `P15-TSK-020` the application's role and both of its alternating users, because a fleet that has rotated signs in as one
     * of them and the others hold every one of its rights, so the writer's own name alone no longer covers its family.
     */
    static final Set<String> WRITING_ROLES = Set.of("finapp_migrator", "postgres", "finapp_app", "finapp_app_a", "finapp_app_b");

    /** The registry is the application's, so the pool's {@code hikaricp.connections.*} are scraped beside the writer's (item 42 (l)). */
    @Bean(destroyMethod = "close")
    ReportingPool reportingPool(Environment environment, MeterRegistry registry) {
        String username = required(environment, ReportingPool.USERNAME_PROPERTY);
        refuseAWritingRole(username, writerUsername(environment));
        return new ReportingPool(
                DatabaseEndpoint.url(environment),
                Binder.get(environment)
                        .bind(WRITER_DRIVER_PROPERTIES, Bindable.mapOf(String.class, String.class))
                        .orElse(Map.of()),
                username,
                required(environment, ReportingPool.LOGIN_PROPERTY),
                Integer.parseInt(required(environment, ReportingPool.POOL_SIZE_PROPERTY)),
                registry);
    }

    /**
     * Refuses a reporting user that is the writer's own role, the migrator or the superuser. PostgreSQL folds an unquoted
     * role name to lower case, so the comparison does too.
     */
    static void refuseAWritingRole(String reportingUser, Optional<String> writerUser) {
        String reporting = reportingUser.trim().toLowerCase(Locale.ROOT);
        boolean writer = writerUser.map(name -> name.trim().toLowerCase(Locale.ROOT)).filter(reporting::equals).isPresent();
        if (writer || WRITING_ROLES.contains(reporting)) {
            throw new IllegalStateException("Refusing to start: " + ReportingPool.USERNAME_PROPERTY + " names "
                    + (writer ? "the application's own database role" : "a role that writes (" + reporting + ")")
                    + ", so report generation would read with every privilege the reporting pool exists to remove."
                    + " Set FINAPP_DB_REPORTING_USER to the SELECT-only reporting role.");
        }
    }

    /** The writer's user as its pool connects: Hikari's own property where set, as {@code DatabaseEndpoint} reads the URL. */
    private static Optional<String> writerUsername(Environment environment) {
        return Optional.ofNullable(Binder.get(environment).bind("spring.datasource.hikari.username", String.class)
                        .orElse(null))
                .filter(name -> !name.isBlank())
                .or(() -> Optional.ofNullable(environment.getProperty("spring.datasource.username")))
                .filter(name -> !name.isBlank());
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
