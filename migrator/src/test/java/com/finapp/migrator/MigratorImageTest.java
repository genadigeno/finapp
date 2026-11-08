package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The migrator image's stage, build context and CI wiring, on every build and with no Docker ({@code P15-TSK-004};
 * {@code THREAT_MODEL.md} T12, T33). What only the built image can show (the user, a filesystem of nothing but the base and
 * {@code /migrator}, two builds being the same bytes) is asserted by {@code migrator-image-build.sh}, and what only a
 * database can show by {@code migrator-verify.sh}; both run in CI's {@code migrator-image} job, which this holds wired.
 */
@DisplayName("the migrator image is a non-root stage of the one Dockerfile, with its own context (P15-TSK-004)")
class MigratorImageTest {

    private static final Pattern PINNED = Pattern.compile("^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}$");
    private static final Set<String> MIGRATOR_INSTRUCTIONS = Set.of("ARG", "LABEL", "ADD", "WORKDIR", "USER", "ENTRYPOINT");

    record Stage(String reference, String name, List<String[]> instructions) {}

    private static List<Stage> stages() {
        List<Stage> stages = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String raw : RepositoryFiles.read("Dockerfile").split("\\R", -1)) {
            String line = raw.strip();
            if (line.startsWith("#") || (line.isEmpty() && pending.isEmpty())) {
                continue;
            }
            if (line.endsWith("\\")) {
                pending.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            pending.append(line);
            String joined = pending.toString().strip();
            pending.setLength(0);
            if (joined.isEmpty()) {
                continue;
            }
            String[] parts = joined.split("\\s+", 2);
            if (parts[0].equalsIgnoreCase("FROM")) {
                String[] words = parts[1].split("\\s+");
                stages.add(new Stage(words[0], words.length >= 3 ? words[2] : null, new ArrayList<>()));
            } else {
                stages.get(stages.size() - 1).instructions().add(new String[] {parts[0].toUpperCase(), parts.length > 1 ? parts[1] : ""});
            }
        }
        return stages;
    }

    private static Stage stage(String name) {
        return stages().stream().filter(stage -> name.equals(stage.name())).findFirst().orElseThrow();
    }

    private static List<String> values(Stage stage, String keyword) {
        return stage.instructions().stream().filter(i -> i[0].equals(keyword)).map(i -> i[1]).toList();
    }

    @Test
    @DisplayName("each base has one pin: the migrator and the application both build on the stage that holds it")
    void oneDefinitionOfTheBases() {
        List<Stage> stages = stages();
        List<Stage> pinned = stages.stream().filter(stage -> PINNED.matcher(stage.reference()).matches()).toList();
        assertThat(pinned).as("the JDK and the runtime, nothing else").hasSize(2);
        String file = RepositoryFiles.read("Dockerfile");
        for (Stage stage : pinned) {
            String digest = stage.reference().substring(stage.reference().indexOf("sha256:"));
            assertThat(file.split(digest, -1).length - 1).as("%s appears once", digest).isEqualTo(1);
        }
        Stage runtimeBase = stage("distroless");
        assertThat(pinned).contains(runtimeBase);
        assertThat(runtimeBase.instructions()).as("the base stage adds nothing to either image").isEmpty();
        assertThat(stage("migrator").reference()).isEqualTo("distroless");
        assertThat(stage("runtime").reference()).isEqualTo("distroless");
        assertThat(stages.get(stages.size() - 1).name())
                .as("the application stays the last stage, so a plain build still produces it")
                .isEqualTo("runtime");
    }

    @Test
    @DisplayName("the stage runs as UID and GID 65532 and launches the migrator: no RUN, ENV, CMD or HEALTHCHECK")
    void theStageIsMinimalAndNonRoot() {
        Stage migrator = stage("migrator");
        assertThat(migrator.instructions().stream().map(i -> i[0]).toList())
                .as("configuration and credentials come from the Job, never the image")
                .allMatch(MIGRATOR_INSTRUCTIONS::contains);
        assertThat(values(migrator, "USER")).containsExactly("65532:65532");
        assertThat(values(migrator, "ADD")).as("one tar, built reproducibly").containsExactly("build/image/finapp-migrator.tar /");
        assertThat(values(migrator, "ENTRYPOINT")).containsExactly(
                "[\"/usr/bin/java\", \"-XX:MaxRAMPercentage=75.0\", \"-XX:+ExitOnOutOfMemoryError\", \"-cp\", \"/migrator/lib/*\","
                        + " \"com.finapp.migrator.MigratorMain\"]");
    }

    @Test
    @DisplayName("the build context is the one tar, and nothing named for the shared Dockerfile can replace the application's allow-list")
    void theContextIsItsOwn() {
        assertThat(Files.exists(RepositoryFiles.root().resolve("Dockerfile.dockerignore")))
                .as("BuildKit reads <dockerfile>.dockerignore in preference to any other, so this would replace the root"
                        + " .dockerignore for the application's build too")
                .isFalse();
        List<String> lines = RepositoryFiles.read("migrator/.dockerignore").lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertThat(lines).containsExactly("*", "!build/image/finapp-migrator.tar");
        assertThat(RepositoryFiles.read("infra/scripts/migrator-image-build.sh"))
                .as("its own context root, so migrator/.dockerignore is the one that applies")
                .contains("--file Dockerfile --target migrator")
                .contains("--tag \"$1\" --load migrator ||");
    }

    @Test
    @DisplayName("the build compares two builds from source, and reads the user, labels, base and migrations from the built image")
    void theBuildScriptAssertsTheImage() {
        String script = RepositoryFiles.read("infra/scripts/migrator-image-build.sh");
        assertThat(script.split("--rerun-tasks :migrator:imageTar", -1).length - 1).as("twice, each from source").isEqualTo(2);
        assertThat(script)
                .contains("check-pinned-images.sh\" --offline")
                .contains("buildx build --no-cache")
                .contains("[ \"$FIRST_ID\" != \"$SECOND_ID\" ]")
                .contains("{{range .RootFS.Layers}}")
                .contains("the image runs as")
                .contains("the image sets environment of its own")
                .contains("the image's migrations are not the repository's, byte for byte")
                .contains("the image holds a shell or a package manager");
    }

    @Test
    @DisplayName("CI builds the image, then runs it against a throwaway database, in a job of its own with no secret or extra permission")
    void ciBuildsAndVerifiesIt() {
        Map<String, List<String>> jobs = jobs();
        List<String> job = jobs.get("migrator-image");
        assertThat(job).as("the workflow has a migrator-image job").isNotNull();
        int build = indexOf(job, "./infra/scripts/migrator-image-build.sh");
        int verify = indexOf(job, "./infra/scripts/migrator-verify.sh");
        assertThat(build).as("the build").isNotNegative();
        assertThat(verify).as("the verification runs after the build").isGreaterThan(build);
        assertThat(String.join("\n", job)).doesNotContain("secrets.").doesNotContain("permissions:");
    }

    @Test
    @DisplayName("the acceptance script compares the histories and schemas, and proves the bounds, on the real image")
    void theVerificationCoversTheEntry() {
        assertThat(RepositoryFiles.read("infra/scripts/migrator-verify.sh"))
                .contains("flywayMigrate")
                .contains("the image's schema history is not the build's")
                .contains("pg_dump")
                .contains("the second run applied something")
                .contains("a migration was applied twice, or not at all")
                .contains("a migration behind a held lock")
                .contains("the superuser")
                .contains("was not refused by its own trigger");
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, List<String>> jobs() {
        Pattern jobHeader = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
        Map<String, List<String>> jobs = new LinkedHashMap<>();
        boolean inJobs = false;
        String current = null;
        for (String raw : RepositoryFiles.read(".github/workflows/ci.yml").split("\\R", -1)) {
            Matcher comment = Pattern.compile("(^|\\s)#").matcher(raw);
            String line = comment.find() ? raw.substring(0, comment.start()) : raw;
            if (line.startsWith("jobs:")) {
                inJobs = true;
                continue;
            }
            Matcher job = jobHeader.matcher(line);
            if (inJobs && job.matches()) {
                current = job.group(1);
                jobs.put(current, new ArrayList<>());
            } else if (current != null) {
                jobs.get(current).add(line);
            }
        }
        return jobs;
    }
}
