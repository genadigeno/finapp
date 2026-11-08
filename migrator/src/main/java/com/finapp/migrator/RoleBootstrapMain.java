package com.finapp.migrator;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The one-time bootstrap step, run by hand or by a separate Job, once per database and again only when a role is added
 * ({@code DATA_MIGRATIONS.md} section 2.2). <strong>Never part of the migration Job</strong>: that Job's image entrypoint is
 * {@link MigratorMain}, it sets no {@code command} of its own, and it does not hold the master user's credential, which only
 * this step reads.
 *
 * <p>It runs from the migrator image with its {@code command} overridden, so a deployment has no third image to build, scan
 * and sign. The image holds no credential either way; what separates the two is which secret each one is handed.
 */
public final class RoleBootstrapMain {

    /** {@code 42710}: another database's bootstrap created the role between this run's check and its {@code CREATE}. */
    private static final String DUPLICATE_OBJECT = "42710";

    private static final Set<String> MASTER_NAMES = Set.of("finapp_migrator", "finapp_app", "finapp_reporting");

    private RoleBootstrapMain() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out).getCode());
    }

    static ExitCode run(String[] args, Map<String, String> environment, PrintStream out) {
        try {
            if (args.length != 0) {
                throw new RefusedConfiguration("this process takes no arguments");
            }
            String url = environment.get("FINAPP_DB_URL");
            DatabaseUrl.verify(url);
            String master = environment.get("FINAPP_DB_MASTER_USER");
            String masterPassword = environment.get("FINAPP_DB_MASTER_PASSWORD");
            if (master == null || master.isBlank() || masterPassword == null || masterPassword.isBlank()) {
                throw new RefusedConfiguration("FINAPP_DB_MASTER_USER and FINAPP_DB_MASTER_PASSWORD are required");
            }
            if (MASTER_NAMES.contains(master)) {
                throw new RefusedConfiguration("the master user cannot be one of the roles it creates");
            }
            Map<RoleBootstrap.Role, String> passwords = RoleBootstrap.passwords(environment);
            DataSource dataSource = MigratorEnvironment.dataSource(url, master, masterPassword, "finapp-bootstrap");
            return applyTwice(dataSource, passwords, out);
        } catch (RefusedConfiguration refused) {
            out.println("finapp-bootstrap result=" + ExitCode.REFUSED + " reason=" + refused.getMessage());
            return ExitCode.REFUSED;
        } catch (SQLException failure) {
            return failed(failure, out);
        } catch (RuntimeException failure) {
            return failed(failure, out);
        }
    }

    private static ExitCode failed(Exception failure, PrintStream out) {
        ExitCode exit = FailureClass.of(failure).exitCode();
        out.println("finapp-bootstrap result=" + exit + " " + failure.getClass().getSimpleName()
                + " " + (failure instanceof SQLException sql ? sql.getSQLState() : ""));
        return exit;
    }

    private static ExitCode applyTwice(DataSource dataSource, Map<RoleBootstrap.Role, String> passwords, PrintStream out)
            throws SQLException {
        try {
            return applyOnce(dataSource, passwords, out);
        } catch (SQLException collision) {
            if (!DUPLICATE_OBJECT.equals(collision.getSQLState())) {
                throw collision;
            }
            return applyOnce(dataSource, passwords, out);
        }
    }

    private static ExitCode applyOnce(DataSource dataSource, Map<RoleBootstrap.Role, String> passwords, PrintStream out)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            for (String line : RoleBootstrap.apply(connection, passwords)) {
                out.println("finapp-bootstrap " + line);
            }
        }
        out.println("finapp-bootstrap result=" + ExitCode.APPLIED);
        return ExitCode.APPLIED;
    }
}
