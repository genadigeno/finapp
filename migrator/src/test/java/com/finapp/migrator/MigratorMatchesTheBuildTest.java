package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The migrator runs every module's migrations exactly as the build does ({@code P15-TSK-004}; {@code THREAT_MODEL.md} T12).
 *
 * <p>Both sides are the real thing: the settings are parsed from each module's {@code flyway} block, and compared with the
 * {@link FluentConfiguration} the migrator will actually hand Flyway. The order is {@code MigratorOrderIsTheBuildsTest}, in
 * {@code app}'s architecture tier. That the resulting schema history is the build's, byte for byte, is shown by running the
 * image: {@code migrator-verify.sh}.
 */
@DisplayName("the migrator applies every module's migrations with the build's order and settings (P15-TSK-004)")
class MigratorMatchesTheBuildTest {

    private static final Pattern BLOCK = Pattern.compile("(?ms)^flyway \\{\\R(.*?)^}\\s*$");

    private static final Path SQL_ROOT = Path.of("/migrator/sql");

    @Test
    @DisplayName("every schema's Flyway is configured as its build.gradle.kts configures it, setting by setting")
    void everySchemaHasTheBuildsSettings() {
        Map<String, String> mismatches = new TreeMap<>();
        for (String schema : MigrationPlan.schemasInBuildOrder()) {
            String build = RepositoryFiles.read(schema + "/build.gradle.kts");
            Matcher block = BLOCK.matcher(build);
            assertThat(block.find()).as("%s has a flyway block", schema).isTrue();
            String settings = block.group(1).replaceAll("(?m)//.*$", "");
            FluentConfiguration runner = MigrationPlan.configurationFor(schema, new PGSimpleDataSource(), SQL_ROOT);

            compare(mismatches, schema, "schemas", single(array(settings, "schemas")), single(List.of(runner.getSchemas())));
            compare(mismatches, schema, "defaultSchema", string(settings, "defaultSchema"), runner.getDefaultSchema());
            // The directory differs by design, a checkout against the image; the location's kind and its schema must not.
            compare(mismatches, schema, "locations", single(array(settings, "locations")),
                    "filesystem:src/main/resources/db/migration/" + lastSegment(runner.getLocations()[0].getDescriptor()));
            compare(mismatches, schema, "cleanDisabled", bool(settings, "cleanDisabled"), runner.isCleanDisabled());
            compare(mismatches, schema, "validateOnMigrate", bool(settings, "validateOnMigrate"), runner.isValidateOnMigrate());
            compare(mismatches, schema, "outOfOrder", bool(settings, "outOfOrder"), runner.isOutOfOrder());
            compare(mismatches, schema, "baselineOnMigrate", bool(settings, "baselineOnMigrate"), runner.isBaselineOnMigrate());
        }
        assertThat(mismatches).as("a setting that differs between the build and the deployed runner").isEmpty();
    }

    @Test
    @DisplayName("the runner's Flyway is the plugin's: one version in the catalog, resolved to it in the lockfile")
    void theRunnerUsesTheBuildsFlyway() {
        Matcher version = Pattern.compile("(?m)^flyway = \"([^\"]+)\"").matcher(RepositoryFiles.read("gradle/libs.versions.toml"));
        assertThat(version.find()).isTrue();
        String lock = RepositoryFiles.read("migrator/gradle.lockfile");
        assertThat(lock)
                .as("Flyway computes the checksums the build stored; another version need not agree with them")
                .contains("org.flywaydb:flyway-core:" + version.group(1) + "=")
                .contains("org.flywaydb:flyway-database-postgresql:" + version.group(1) + "=");
    }

    @Test
    @DisplayName("a statement waits for a lock for less time than a request waits for a pooled connection")
    void theLockBoundEndsBeforeThePoolGivesUp() {
        Matcher poolTimeout = Pattern.compile("(?m)^\\s+connection-timeout:\\s*(\\d+)\\s*$")
                .matcher(RepositoryFiles.read("app/src/main/resources/application.yaml"));
        assertThat(poolTimeout.find()).as("application.yaml's connection-timeout was read").isTrue();
        assertThat(MigrationPlan.LOCK_TIMEOUT.toMillis())
                .as("a DDL queued behind a long transaction stalls every later request for that table, each holding a"
                        + " pooled connection: the queue must drain before a caller gives up on the pool")
                .isPositive()
                .isLessThan(Long.parseLong(poolTimeout.group(1)));
        assertThat(MigrationPlan.SESSION_OPTIONS)
                .as("in the startup packet, in the unit PostgreSQL reads a bare number in")
                .isEqualTo("-c lock_timeout=" + MigrationPlan.LOCK_TIMEOUT.toMillis());
    }

    @Test
    @DisplayName("a missing directory is a refusal, and a wait for another run's lock is bounded")
    void whatOnlyADeployedRunnerNeeds() {
        FluentConfiguration runner = MigrationPlan.configurationFor("ledger", new PGSimpleDataSource(), SQL_ROOT);
        assertThat(runner.isFailOnMissingLocations()).as("an image built without a schema's scripts must not pass as up to date").isTrue();
        assertThat(runner.getLockRetryCount()).as("bounded: -1 would wait for ever").isPositive();
        assertThat(runner.isGroup()).as("the build's: one transaction per migration").isFalse();
    }

    private static void compare(Map<String, String> mismatches, String schema, String setting, Object build, Object runner) {
        if (!build.equals(runner)) {
            mismatches.put(schema + "." + setting, "build " + build + ", runner " + runner);
        }
    }

    private static String lastSegment(String descriptor) {
        return descriptor.substring(Math.max(descriptor.lastIndexOf('/'), descriptor.lastIndexOf('\\')) + 1);
    }

    private static String single(List<String> values) {
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private static List<String> array(String settings, String name) {
        Matcher matcher = assignment(settings, name, "arrayOf\\((.*)\\)");
        List<String> values = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(matcher.group(1));
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    private static String string(String settings, String name) {
        return assignment(settings, name, "\"([^\"]*)\"").group(1);
    }

    private static boolean bool(String settings, String name) {
        return Boolean.parseBoolean(assignment(settings, name, "(true|false)").group(1));
    }

    /** Exactly one assignment of the setting: a second, later one would override what a first match read. */
    private static Matcher assignment(String settings, String name, String valuePattern) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + name + "\\s*=\\s*" + valuePattern + "\\s*$").matcher(settings);
        assertThat(matcher.find()).as("the assignment of %s", name).isTrue();
        assertThat(Pattern.compile("\\b" + name + "\\b").matcher(settings).results().count())
                .as("%s is assigned once and named nowhere else in the block", name)
                .isEqualTo(1);
        return matcher;
    }
}
