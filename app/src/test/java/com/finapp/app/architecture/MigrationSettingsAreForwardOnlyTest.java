package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every module that owns migrations configures its Flyway tasks forward-only ({@code P15-TSK-001}'s fix round;
 * {@code THREAT_MODEL.md} T12).
 *
 * <p>The register first cited two build files by existence, which deleting {@code cleanDisabled = true} would not
 * have turned red. This holds the three settings in <em>every</em> module with a {@code db/migration} directory,
 * found by walking the repository rather than listing modules. It holds the build's Flyway tasks, the path CI's
 * {@code migrations} job and a developer use. It does not hold the deployed runner: that is the migrator image,
 * which {@code P15-TSK-004} builds with the build's settings.
 */
@Tag("architecture")
@DisplayName("every migrating module's Flyway tasks are forward-only (P15-TSK-001)")
class MigrationSettingsAreForwardOnlyTest {

    /** Each setting and the one value it may take. */
    private static final Map<String, String> SETTINGS =
            Map.of("cleanDisabled", "true", "validateOnMigrate", "true", "outOfOrder", "false");

    @Test
    @DisplayName("clean disabled, validated before migrating and never out of order, in every module with migrations")
    void everyMigratingModuleIsForwardOnly() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> modules;
        try (Stream<Path> children = Files.list(root)) {
            modules = children
                    .filter(module -> Files.isDirectory(module.resolve("src/main/resources/db/migration")))
                    .sorted()
                    .toList();
        }
        Map<String, String> missing = new TreeMap<>();
        for (Path module : modules) {
            List<String> build = read(module.resolve("build.gradle.kts")).lines()
                    .map(line -> line.replaceFirst("//.*$", ""))
                    .toList();
            SETTINGS.forEach((setting, value) -> {
                // Every line naming the setting must be the one assignment: a later "cleanDisabled = false", or a
                // setter, overrides the first, so the first match proves nothing.
                Pattern names = Pattern.compile("\\b" + setting + "\\b");
                Pattern assigns = Pattern.compile("^\\s*" + setting + "\\s*=\\s*" + value + "\\s*$");
                List<String> naming = build.stream().filter(line -> names.matcher(line).find()).toList();
                if (naming.isEmpty() || !naming.stream().allMatch(line -> assigns.matcher(line).matches())) {
                    missing.merge(module.getFileName().toString(), setting + " = " + value + " (and only that)",
                            (a, b) -> a + ", " + b);
                }
            });
        }
        assertThat(modules.stream().map(module -> module.getFileName().toString()).toList())
                .as("the walk found the migrating modules: the ledger and the platform at least, and every one")
                .contains("ledger", "platform")
                .hasSizeGreaterThanOrEqualTo(21);
        assertThat(missing)
                .as("a module whose migrations can be cleaned, run out of order or applied unvalidated is not forward-only")
                .isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
