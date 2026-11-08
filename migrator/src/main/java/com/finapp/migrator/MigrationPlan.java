package com.finapp.migrator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * What the migrator applies and how: the same settings per schema as each module's {@code build.gradle.kts}, in the
 * build's order. {@code MigratorMatchesTheBuildTest} compares this class's real configuration with those files.
 *
 * <p><strong>{@code Flyway.configure()} reads no environment and no configuration file</strong>: only what is written
 * here applies. A {@code FLYWAY_CLEAN_DISABLED=false} left in a Job's environment does nothing; it would have reached
 * the Gradle plugin, which is a reason this path is the safer one to deploy.
 */
final class MigrationPlan {

    /**
     * How long one statement may wait for a lock: <strong>below the application pool's
     * {@code connection-timeout}</strong> (5 s, read by a test). PostgreSQL queues lock requests in arrival order, so
     * while a DDL statement waits for {@code ACCESS EXCLUSIVE} behind one long transaction, every later reader and writer
     * of that table waits behind the DDL, and each of those holds a pooled connection. The wait must end, and the
     * queue drain, before a request waiting for a connection gives up.
     */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(3);

    /** How many times one schema's migrations are tried when a lock could not be had, and how long apart. */
    static final int LOCK_ATTEMPTS = 5;

    static final Duration LOCK_BACKOFF = Duration.ofSeconds(5);

    /** Flyway waits one second between polls of its schema-history lock: about five minutes for one migration of another run's. */
    static final int HISTORY_LOCK_RETRIES = 300;

    /** Set on every connection, so an operator reading {@code pg_stat_activity} can see who this is. */
    static final String APPLICATION_NAME = "finapp-migrator";

    /** The startup-packet form: in force before any statement, kept through {@code RESET}, above {@code ALTER ROLE ... SET}. */
    static final String SESSION_OPTIONS = "-c lock_timeout=" + LOCK_TIMEOUT.toMillis();

    /** Where the image holds each schema's scripts: {@code <root>/<schema>/V*.sql}. */
    static final Path SQL_ROOT = Path.of("/migrator/sql");

    private static final String ORDER_RESOURCE = "/com/finapp/migrator/schema-order.txt";

    private MigrationPlan() {}

    /** The schemas in the build's order, read from the one definition the image's build also reads. */
    static List<String> schemasInBuildOrder() {
        try (InputStream order = Objects.requireNonNull(
                        MigrationPlan.class.getResourceAsStream(ORDER_RESOURCE), ORDER_RESOURCE);
                BufferedReader reader = new BufferedReader(new InputStreamReader(order, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the migration order", e);
        }
    }

    /**
     * One schema's Flyway, exactly as its build script configures it, plus the two things only a deployed runner needs: a
     * bound on waiting for another run, and a refusal to treat a missing directory as "no migrations".
     */
    static FluentConfiguration configurationFor(String schema, DataSource dataSource, Path sqlRoot) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("filesystem:" + sqlRoot.resolve(schema))
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .baselineOnMigrate(false)
                .failOnMissingLocations(true)
                .lockRetryCount(HISTORY_LOCK_RETRIES);
    }
}
