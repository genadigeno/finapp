package com.finapp.migrator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.internal.exception.FlywayMigrateException;

/**
 * The migrator image's entrypoint: every module's migrations, as {@code finapp_migrator}, once per release, before the
 * rollout ({@code PHASE_15_PLAN.md} section 4; ADR-0011, ADR-0120).
 *
 * <p>It does one thing and takes no arguments, so a Job has no way to ask it for anything else. Roles are not here:
 * {@link RoleBootstrapMain} is a separate process, with a credential this class never reads.
 */
public final class MigratorMain {

    private MigratorMain() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), MigrationPlan.SQL_ROOT, System.out).getCode());
    }

    static ExitCode run(String[] args, Map<String, String> environment, Path sqlRoot, PrintStream out) {
        try {
            if (args.length != 0) {
                throw new RefusedConfiguration("this process takes no arguments");
            }
            MigratorEnvironment configuration = MigratorEnvironment.from(environment);
            List<String> schemas = MigrationPlan.schemasInBuildOrder();
            verifyEveryScriptIsHere(schemas, sqlRoot);
            DataSource dataSource = configuration.dataSource();
            try (Connection connection = dataSource.getConnection()) {
                ConnectedRole.verify(connection, MigrationPlan.LOCK_TIMEOUT.toMillis());
            }
            return new MigrationRun(
                            new FlywayMigrations(dataSource, sqlRoot),
                            duration -> Thread.sleep(duration.toMillis()),
                            MigrationPlan.LOCK_ATTEMPTS,
                            MigrationPlan.LOCK_BACKOFF,
                            out)
                    .apply(schemas);
        } catch (RefusedConfiguration refused) {
            out.println("finapp-migrator result=" + ExitCode.REFUSED + " reason=" + refused.getMessage());
            return ExitCode.REFUSED;
        } catch (SQLException | RuntimeException failure) {
            return before(failure, out);
        }
    }

    /**
     * A failure before the first migration: the database not being there yet, or an identity it will not have. The
     * statement is never printed, and neither is the URL's value.
     */
    private static ExitCode before(Exception failure, PrintStream out) {
        String sqlState = failure instanceof SQLException sql ? sql.getSQLState() : null;
        // Class 28 is a credential the server refused; 3D000 a database that is not there. Neither gets better by retrying.
        ExitCode exit = sqlState != null && (sqlState.startsWith("28") || sqlState.equals("3D000"))
                ? ExitCode.REFUSED
                : FailureClass.of(failure).exitCode();
        out.println("finapp-migrator result=" + exit + " before the first schema: " + failure.getClass().getSimpleName()
                + " " + (sqlState == null ? "" : sqlState));
        return exit;
    }

    /** Flyway reads a directory with no scripts as "nothing to do", so an image built wrongly would pass as up to date. */
    private static void verifyEveryScriptIsHere(List<String> schemas, Path sqlRoot) {
        for (String schema : schemas) {
            Path directory = sqlRoot.resolve(schema);
            try (Stream<Path> files = Files.list(directory)) {
                if (files.noneMatch(file -> file.getFileName().toString().endsWith(".sql"))) {
                    throw new RefusedConfiguration("no migrations in " + directory);
                }
            } catch (java.io.IOException e) {
                throw new RefusedConfiguration("cannot read " + directory);
            }
        }
    }

    /** The real thing behind {@link MigrationRun.SchemaMigration}: counts what a retried attempt had applied before it failed. */
    private static final class FlywayMigrations implements MigrationRun.SchemaMigration {

        private final DataSource dataSource;
        private final Path sqlRoot;
        private final Map<String, Integer> appliedBeforeFailing = new HashMap<>();

        FlywayMigrations(DataSource dataSource, Path sqlRoot) {
            this.dataSource = dataSource;
            this.sqlRoot = sqlRoot;
        }

        @Override
        public int migrate(String schema) {
            try {
                int applied = MigrationPlan.configurationFor(schema, dataSource, sqlRoot).load().migrate().migrationsExecuted;
                return applied + appliedBeforeFailing.getOrDefault(schema, 0);
            } catch (FlywayMigrateException failure) {
                appliedBeforeFailing.merge(schema, failure.getErrorResult().migrationsExecuted, Integer::sum);
                throw failure;
            }
        }
    }
}
