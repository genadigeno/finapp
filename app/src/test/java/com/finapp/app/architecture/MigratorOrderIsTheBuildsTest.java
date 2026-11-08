package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The migrator applies every schema in the build's order ({@code P15-TSK-004}; {@code THREAT_MODEL.md} T12).
 *
 * <p>{@code ./gradlew flywayMigrate}, unqualified, runs the task in every project that has it, in project path order
 * (measured with {@code --dry-run}, 2026-11-08). So the build's order is derived here from {@code settings.gradle.kts} and
 * each module's build script, and the migrator's is the file it and the image's build both read. Everything else about
 * the two being the same, setting by setting, is {@code MigratorMatchesTheBuildTest} in the {@code migrator} module, against
 * the configuration the runner really hands Flyway.
 */
@Tag("architecture")
@DisplayName("the migrator applies every schema, in the build's order (P15-TSK-004)")
class MigratorOrderIsTheBuildsTest {

    private static final String ORDER_FILE = "migrator/src/main/resources/com/finapp/migrator/schema-order.txt";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^include\\(\"([a-z]+)\"\\)\\s*$");
    private static final Pattern FLYWAY_PLUGIN = Pattern.compile("(?m)^\\s*alias\\(libs\\.plugins\\.flyway\\)\\s*$");

    private static List<String> pinned() {
        return EvidenceCitations.read(EvidenceCitations.repositoryRoot().resolve(ORDER_FILE)).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    @Test
    @DisplayName("the pinned order is the build's: every schema-owning module, in Gradle's project path order")
    void theOrderIsTheBuilds() {
        Path root = EvidenceCitations.repositoryRoot();
        List<String> build = INCLUDE.matcher(EvidenceCitations.read(root.resolve("settings.gradle.kts"))).results()
                .map(match -> match.group(1))
                .filter(module -> FLYWAY_PLUGIN.matcher(EvidenceCitations.read(root.resolve(module).resolve("build.gradle.kts"))).find())
                .sorted()
                .toList();
        assertThat(build).as("the walk found the build's schema owners").contains("ledger", "platform").hasSizeGreaterThanOrEqualTo(21);
        assertThat(pinned())
                .as("a module that owns a schema and is not applied, or two applied in another order than every tested database"
                        + " was built in")
                .containsExactlyElementsOf(build);
    }

    @Test
    @DisplayName("every directory of migrations in the repository is one the migrator applies")
    void noMigrationsAreLeftBehind() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<String> found;
        try (Stream<Path> children = Files.list(root)) {
            found = children
                    .filter(module -> Files.isDirectory(module.resolve("src/main/resources/db/migration").resolve(module.getFileName())))
                    .map(module -> module.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertThat(found).as("the walk found the migrating modules").hasSizeGreaterThanOrEqualTo(21).isEqualTo(pinned().stream().sorted().toList());
    }
}
