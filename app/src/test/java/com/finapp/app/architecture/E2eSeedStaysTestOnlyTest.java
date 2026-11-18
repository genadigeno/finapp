package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Operator Console's end-to-end seed (`P17-TSK-106`) is a test-only path, and stays one: it lives under {@code infra/scripts/}, no
 * image carries it, nothing deployed runs it, and it refuses any stack but a test one.
 *
 * <p>The images are built from nothing else by construction: the application image's build context is the boot jar alone
 * ({@code .dockerignore}, held by {@link ApplicationImageTest}), and the migrator image's is every module's
 * {@code src/main/resources/db/migration} ({@code migrator/build.gradle.kts}). So "ships nowhere" is "named by no {@code src/main}
 * file", which this holds by the seed's own markers. What the plants do is held by {@code E2eSeedPlantsDatabaseTest} on a real
 * PostgreSQL; the script's run against a running stack is the console's end-to-end run.
 */
@Tag("architecture")
@DisplayName("the end-to-end seed is test-only and stays so (P17-TSK-106)")
class E2eSeedStaysTestOnlyTest {

    private static final String SCRIPT = "infra/scripts/e2e-seed.sh";
    private static final String SEED = "infra/scripts/e2e-seed/";

    /** What only the seed says: its script's name, its event type and aggregate type, its consumer and group, its plant's failure. */
    private static final Pattern MARKERS =
            Pattern.compile("e2e-seed|e2e\\.SeededEvent|e2e\\.Seed\\b|e2e\\.seed-consumer|finapp\\.e2e-seed|E2eSeedPlantedFailure");

    private static Path root;
    private static String script;

    @BeforeAll
    static void readTheSeed() {
        root = EvidenceCitations.repositoryRoot();
        script = EvidenceCitations.read(root.resolve(SCRIPT));
    }

    @Test
    @DisplayName("no module's main source set names the seed, so neither the application's jar nor the migrator's migrations carry it")
    void noImageCarriesTheSeed() throws IOException {
        List<Path> main;
        try (Stream<Path> modules = Files.list(root)) {
            main = modules.map(module -> module.resolve("src/main")).filter(Files::isDirectory).toList();
        }
        assertThat(main).as("the modules' main source sets were found").hasSizeGreaterThan(10);
        List<String> naming = main.stream().flatMap(E2eSeedStaysTestOnlyTest::files).filter(E2eSeedStaysTestOnlyTest::namesTheSeed)
                .map(file -> root.relativize(file).toString()).toList();
        assertThat(naming).as("the seed and its rows belong to tests: a main source set that names them ships them").isEmpty();
    }

    @Test
    @DisplayName("nothing deployed names the seed: no manifest, no delivery root, no Terraform")
    void nothingDeployedRunsTheSeed() {
        List<String> naming = Stream.of("deploy", "infra/terraform").map(root::resolve).filter(Files::isDirectory)
                .flatMap(E2eSeedStaysTestOnlyTest::files).filter(E2eSeedStaysTestOnlyTest::namesTheSeed)
                .map(file -> root.relativize(file).toString()).toList();
        assertThat(naming).isEmpty();
    }

    @Test
    @DisplayName("every plant is marked test-only and is one statement, so psql and the database test run the same text")
    void everyPlantIsOneMarkedStatement() {
        List<Path> plants = files(root.resolve(SEED)).filter(file -> file.toString().endsWith(".sql")).toList();
        assertThat(plants).extracting(file -> file.getFileName().toString())
                .containsExactlyInAnyOrder("abandoned-event.sql", "parked-record.sql");
        for (Path plant : plants) {
            String text = EvidenceCitations.read(plant);
            assertThat(text).as("%s says what it is", plant.getFileName()).startsWith("-- TEST-ONLY (P17-TSK-106)");
            String statements = text.replaceAll("(?m)--.*$", "").replaceAll("'[^']*'", "''").strip();
            assertThat(statements.chars().filter(c -> c == ';').count()).as("%s is one statement", plant.getFileName()).isEqualTo(1);
            assertThat(statements).as("%s ends in that statement", plant.getFileName()).endsWith(";");
            assertThat(statements).as("%s writes the outbox's tables and nothing else", plant.getFileName())
                    .doesNotContainPattern("(?i)\\b(UPDATE|DELETE|TRUNCATE|ALTER|DROP|GRANT|CREATE)\\b");
        }
    }

    @Test
    @DisplayName("the script refuses any stack but a test one before it touches anything")
    void theScriptRefusesAnythingButATestStack() {
        assertThat(script).startsWith("#!/bin/sh").contains("set -eu");
        assertThat(script).as("the compose target answers only on this machine, anchored so no lookalike host passes")
                .contains("grep -Eq '^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?/?$'")
                .contains("|| refuse \"FINAPP_BACKEND_URL is");
        assertThat(script).as("the kind target runs only against a kind node, read from the node's own label")
                .contains("{{index .Config.Labels \"io.x-k8s.kind.cluster\"}}")
                .contains("refuse \"E2E_SEED_NODE '$E2E_SEED_NODE' is not a kind node");
        assertThat(script).as("an unknown target is refused, never defaulted to something live")
                .containsPattern("\\*\\)\\s+refuse \"E2E_SEED_TARGET is");
        int refusals = script.indexOf("refuse \"E2E_SEED_TARGET is");
        assertThat(script.indexOf("psql")).as("the refusals come before the first database statement").isGreaterThan(refusals);
    }

    @Test
    @DisplayName("the HTTP half has no route to the database, and what the shell puts into SQL is checked first")
    void theHttpHalfHasNoDatabaseRoute() {
        String seed = EvidenceCitations.read(root.resolve(SEED + "Seed.java"));
        assertThat(seed).as("Seed.java reaches the platform through its doors only")
                .doesNotContain("java.sql").doesNotContainIgnoringCase("jdbc").doesNotContain("psql");
        assertThat(seed).as("a test identity's secrets stay in the run's state, never on standard output")
                .doesNotContainPattern("System\\.out\\.println\\([^)]*(password|totp|secret)");
        int checked = script.indexOf("grep -Eq '^e2e-seed\\.[a-z]+\\.[0-9a-f]{12}$'");
        assertThat(checked).as("a login's shape is checked before it reaches SQL").isPositive();
        assertThat(script.indexOf("WHERE login_identifier = '$login'")).isGreaterThan(checked);
        assertThat(script).as("the run's state, with its test identities' secrets, goes with the run").contains("trap cleanup EXIT");
    }

    @Test
    @DisplayName("the script is committed executable, as every script under infra/scripts")
    void theScriptIsExecutable() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-s", SCRIPT).directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the script is tracked").isNotBlank().startsWith("100755");
    }

    private static Stream<Path> files(Path directory) {
        try {
            return Files.walk(directory).filter(Files::isRegularFile).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + directory, e);
        }
    }

    private static boolean namesTheSeed(Path file) {
        try {
            return MARKERS.matcher(Files.readString(file, StandardCharsets.ISO_8859_1)).find();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
