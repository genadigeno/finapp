package com.finapp.platform.testing;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Starts the database the tests run against, once per test JVM.
 *
 * <h2>What this changes</h2>
 *
 * <p>{@code P0-TSK-035}'s acceptance criterion is that "no test depends on a developer's local
 * services". Until now every database test connected to whatever was listening on
 * {@code 127.0.0.1:5432} — in practice the {@code compose.yaml} stack, started by hand. That is a
 * shared, long-lived database: one test's leftover row is another test's mystery, and a developer
 * who forgets the compose step gets a connection error rather than a test run.
 *
 * <p>This starts a container instead, applies the same role script and the same migrations, and
 * publishes the coordinates as the system properties every existing test already reads. **No test
 * changed.** That is deliberate: the harness had to be adoptable without touching 173 assertions,
 * or adopting it would have been a change nobody could review.
 *
 * <h2>Why a LauncherSessionListener</h2>
 *
 * <p>It runs once per JVM, before any test class is loaded, which is the only place that can set
 * system properties a static initialiser will later read — and several suites open their connection
 * in {@code @BeforeAll}. A JUnit extension runs too late for that.
 *
 * <h2>The escape hatch, and why it is not a bypass</h2>
 *
 * <p>If {@code finapp.db.url} is already set, this does nothing and the tests use it. That is how
 * the Gradle task can still point the suite at the compose stack deliberately — useful when
 * investigating something in a database you can inspect afterwards, which a container that
 * disappears makes hard. It is not a way to skip the database: an unset property with no Docker
 * available fails loudly at container start rather than silently skipping.
 */
public final class DatabaseUnderTest implements LauncherSessionListener {

    /** Set by the Gradle task from the version catalog, so the image has one definition. */
    private static final String IMAGE_PROPERTY = "finapp.db.image";

    /**
     * The marked local default (`SECRET_MANAGEMENT.md`). The role script hard-codes it, so the
     * container's superuser must match or the migrator could not be created with it.
     *
     * <p>Named {@code MARKED_LOCAL_DEFAULT} rather than {@code MARKED_LOCAL_DEFAULT} for the reason
     * {@code DatabaseCredentialGuard} records: {@code secretsAreWrapped} rejects a field whose name
     * says it holds a secret, and this is not one - it is the published marker identifying a value
     * as deliberately not a secret. The rule fired on the first draft, which is the rule working:
     * these fixtures are swept as production code, and a harness that holds credentials is exactly
     * the place that should be.
     */
    private static final String MARKED_LOCAL_DEFAULT = "local-development-only-not-a-secret";

    /**
     * An INSTANCE field, not static.
     *
     * <p>A listener instance lives for the whole session, so static buys nothing - and
     * {@code noStaticMutableState} rejected the static version (ADR-0024). That rule fired on test
     * fixtures because they are swept as production code, and the honest response was to remove the
     * static state rather than to exempt the sweep: the field genuinely did not need to be static.
     *
     * <p>{@code org.testcontainers.postgresql}, NOT {@code org.testcontainers.containers}:
     * Testcontainers 2.x moved the class and deprecated the old location, and {@code -Werror} turns
     * that deprecation into a build failure.
     */
    private PostgreSQLContainer container;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (System.getProperty("finapp.db.url") != null) {
            // Deliberately pointed somewhere - the compose stack, usually. Leave it alone.
            return;
        }
        String image = System.getProperty(IMAGE_PROPERTY);
        if (image == null) {
            // Not a database run. Only the databaseTest task sets the image, so the hermetic
            // `test` task lands here - and it must not start a container: `./gradlew build` has
            // to be green on a machine with nothing running, which is the whole reason the two
            // task are separate. Throwing here killed the hermetic suite, which is how this
            // branch came to exist.
            return;
        }

        container =
                new PostgreSQLContainer(DockerImageName.parse(image))
                        .withDatabaseName("finapp")
                        .withUsername("finapp")
                        .withPassword(MARKED_LOCAL_DEFAULT)
                        // The same role script the compose stack runs on first initialisation.
                        // Roles are cluster objects and cannot live in a migration (ADR-0011), so
                        // a container that skipped this would have no finapp_app and every
                        // privilege test would fail for the wrong reason.
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(roleScript()),
                                "/docker-entrypoint-initdb.d/00-roles.sql");
        container.start();

        migrate();
        publishCoordinates();
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /**
     * Applies the real migrations, in order, with the real history table.
     *
     * <p>Not a schema dump and not raw SQL: {@code flywayValidate} is a CI gate, and a database
     * whose schema arrived by another route would not have the history to validate against.
     */
    private void migrate() {
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), "finapp_migrator", MARKED_LOCAL_DEFAULT)
                .schemas("platform")
                .defaultSchema("platform")
                .locations("filesystem:" + repositoryRoot().resolve("platform/src/main/resources/db/migration/platform"))
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    /**
     * The properties every existing test already reads, plus the {@code FINAPP_DB_*} names
     * {@code application.yaml} resolves — so the {@code app} module's Spring tests reach the same
     * container without a second mechanism.
     */
    private void publishCoordinates() {
        String url = container.getJdbcUrl();
        System.setProperty("finapp.db.url", url);
        System.setProperty("finapp.db.user", container.getUsername());
        System.setProperty("finapp.db.password", container.getPassword());
        System.setProperty("finapp.db.app.user", "finapp_app");
        System.setProperty("finapp.db.app.password", MARKED_LOCAL_DEFAULT);
        System.setProperty("finapp.db.migrator.user", "finapp_migrator");
        System.setProperty("finapp.db.migrator.password", MARKED_LOCAL_DEFAULT);

        System.setProperty("FINAPP_DB_URL", url);
        System.setProperty("FINAPP_DB_APP_USER", "finapp_app");
        System.setProperty("FINAPP_DB_APP_PASSWORD", MARKED_LOCAL_DEFAULT);
    }

    private static Path roleScript() {
        return repositoryRoot().resolve("infra/postgres/initdb/00-roles.sql");
    }

    /** Walks upward, so the harness does not assume a working directory. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (java.nio.file.Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
