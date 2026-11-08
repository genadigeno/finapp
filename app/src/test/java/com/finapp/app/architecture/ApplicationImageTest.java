package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The application image's Dockerfile, build context and CI wiring hold what the threat register counts as built
 * ({@code P15-TSK-003}; {@code THREAT_MODEL.md} T4, T5, T6 and T27; owner decision C4).
 *
 * <p>This reads text, on every build and with no Docker: the pins, the stages, the runtime stage's instructions and
 * the context's allow-list. What only the built image can show - the user it runs as, a filesystem holding nothing
 * but the base's entries and the jar's, two builds being the same bytes, the size budget - is asserted by
 * {@code infra/scripts/image-build.sh} against {@code docker inspect} and {@code docker export}, in CI's
 * {@code image} job, which {@link #theImageIsBuiltComparedAndRunInCiAndItsBasesProposedWeekly} holds wired. The two
 * are blind in different directions: this one fails a pull request before an image exists; the script fails on a
 * base whose contents are not what its name promises.
 */
@Tag("architecture")
@DisplayName("the application image is pinned, minimal and non-root, as the threat register says (P15-TSK-003)")
class ApplicationImageTest {

    private static final String DOCKERFILE = "Dockerfile";
    private static final String DOCKERIGNORE = ".dockerignore";
    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String DEPENDABOT = ".github/dependabot.yml";
    private static final String BUILD_SCRIPT = "infra/scripts/image-build.sh";
    private static final String READINESS_SCRIPT = "infra/scripts/image-readiness.sh";
    private static final String CATALOG = "gradle/libs.versions.toml";

    /** {@code repository:tag@sha256:<64 hex>}: the one pin form, the one {@code check-pinned-images.sh} accepts. */
    private static final Pattern PINNED = Pattern.compile("^(?<repository>[a-z0-9][a-z0-9._-]*(?::[0-9]+)?"
            + "(?:/[a-z0-9][a-z0-9._-]*)*):(?<tag>[A-Za-z0-9_][A-Za-z0-9_.-]*)@sha256:[0-9a-f]{64}$");

    /** The Spring Boot layered jar's layers, least-changing first: the order a code change rebuilds one of. */
    private static final List<String> LAYERS =
            List.of("dependencies", "spring-boot-loader", "snapshot-dependencies", "application");

    /** Everything the runtime stage may say: no {@code RUN}, {@code ADD}, {@code ENV}, {@code HEALTHCHECK} or other. */
    private static final Set<String> RUNTIME_INSTRUCTIONS =
            Set.of("ARG", "LABEL", "COPY", "WORKDIR", "USER", "ENTRYPOINT");

    private static final String RUNTIME_USER = "65532:65532";
    private static final Pattern JOB = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");

    private static Path root;
    private static String toolchain;
    private static List<Stage> stages;

    record Instruction(String keyword, String arguments) {}

    record Stage(String reference, String name, List<Instruction> instructions) {}

    @BeforeAll
    static void readTheDockerfile() {
        root = EvidenceCitations.repositoryRoot();
        Matcher version = Pattern.compile("(?m)^javaToolchain\\s*=\\s*\"(\\d+)\"")
                .matcher(EvidenceCitations.read(root.resolve(CATALOG)));
        assertThat(version.find()).as("javaToolchain in %s", CATALOG).isTrue();
        toolchain = version.group(1);
        stages = stagesOf(root.resolve(DOCKERFILE));
    }

    @Test
    @DisplayName("every FROM of every Dockerfile at the root names an earlier stage or an image pinned by digest")
    void everyBaseIsPinnedByDigest() {
        List<Path> dockerfiles = rootDockerfiles();
        assertThat(dockerfiles).as("the root's Dockerfiles were found").isNotEmpty();
        List<String> unpinned = new ArrayList<>();
        for (Path dockerfile : dockerfiles) {
            List<String> names = new ArrayList<>();
            for (Stage stage : stagesOf(dockerfile)) {
                if (!names.contains(stage.reference()) && !PINNED.matcher(stage.reference()).matches()) {
                    unpinned.add(root.relativize(dockerfile) + ": FROM " + stage.reference());
                }
                if (stage.name() != null) {
                    names.add(stage.name());
                }
            }
        }
        assertThat(stages.stream().filter(stage -> PINNED.matcher(stage.reference()).matches()))
                .as("the application image's two bases, the builder and the runtime, were read as pinned")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(unpinned)
                .as("a tag can be moved by whoever controls the source repository; a digest cannot, and the tag beside"
                        + " it is the version the weekly check holds the digest to")
                .isEmpty();
    }

    @Test
    @DisplayName("the builder is a JDK of the toolchain's Java")
    void theBuilderIsTheToolchainsJdk() {
        Matcher builder = PINNED.matcher(stages.get(0).reference());
        assertThat(builder.matches()).as("the builder is pinned").isTrue();
        assertThat(builder.group("tag"))
                .as("the builder extracts a jar compiled for Java %s", toolchain)
                .startsWith(toolchain + ".")
                .contains("-jdk");
        assertThat(stages.get(0).name()).as("the runtime stage copies from the stage named builder").isEqualTo("builder");
    }

    @Test
    @DisplayName("the runtime is distroless's nonroot Java of the toolchain's version: no shell, no package manager")
    void theRuntimeIsDistrolessNonrootOnTheToolchainsJava() {
        assertThat(stages).as("a builder stage and a runtime stage").hasSizeGreaterThanOrEqualTo(2);
        Matcher runtime = PINNED.matcher(runtime().reference());
        assertThat(runtime.matches()).as("the runtime base is pinned").isTrue();
        assertThat(runtime.group("repository"))
                .as("distroless Java %s: a JRE and its certificates, no shell and no package manager", toolchain)
                .matches("gcr\\.io/distroless/java" + toolchain + "-debian\\d+");
        assertThat(runtime.group("tag"))
                .as("the nonroot variant; distroless's other tags run as root")
                .isEqualTo("nonroot");
    }

    @Test
    @DisplayName("the runtime stage runs as UID and GID 65532, numerically")
    void theRuntimeStageRunsAsUid65532() {
        List<String> users = runtime().instructions().stream()
                .filter(instruction -> instruction.keyword().equals("USER"))
                .map(Instruction::arguments)
                .toList();
        assertThat(users).as("the runtime stage declares its user rather than inheriting the base's").isNotEmpty();
        assertThat(users.get(users.size() - 1))
                .as("explicit, so a change of base cannot make the image root, and numeric, so Kubernetes'"
                        + " runAsNonRoot can verify it")
                .isEqualTo(RUNTIME_USER);
    }

    @Test
    @DisplayName("the runtime stage adds the jar's four layers in order, root-owned, and nothing else")
    void theRuntimeStageAddsTheJarsFourLayersAndNothingElse() {
        List<String> forbidden = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        String entrypoint = null;
        for (Instruction instruction : runtime().instructions()) {
            if (!RUNTIME_INSTRUCTIONS.contains(instruction.keyword())) {
                forbidden.add(instruction.keyword() + " " + instruction.arguments());
            }
            if (instruction.keyword().equals("COPY")) {
                Matcher copy = Pattern.compile("^--from=builder\\s+/workspace/layers/([a-z-]+)/\\s+/$")
                        .matcher(instruction.arguments());
                copied.add(copy.matches() ? copy.group(1) : "unreadable: COPY " + instruction.arguments());
            }
            if (instruction.keyword().equals("ENTRYPOINT")) {
                entrypoint = instruction.arguments();
            }
        }
        assertThat(forbidden)
                .as("no RUN, ADD, ENV, VOLUME, EXPOSE, HEALTHCHECK or SHELL in the runtime stage: it holds the base and"
                        + " the jar's layers, and its configuration comes from the deployment")
                .isEmpty();
        assertThat(copied)
                .as("one COPY per layer from the builder, to the root, with no --chown or --chmod (the process must not"
                        + " own the code it runs), least-changing first")
                .isEqualTo(LAYERS);
        assertThat(entrypoint).as("an ENTRYPOINT").isNotNull();
        assertThat(entrypoint)
                .as("exec form, the JRE launching the jar with the heap sized from the container's limit and an"
                        + " out-of-memory error ending the process")
                .startsWith("[\"/usr/bin/java\"")
                .contains("\"-XX:MaxRAMPercentage=")
                .contains("\"-XX:+ExitOnOutOfMemoryError\"")
                .endsWith("\"org.springframework.boot.loader.launch.JarLauncher\"]");
        assertThat(stages.stream().flatMap(stage -> stage.instructions().stream()).map(Instruction::keyword))
                .as("no HEALTHCHECK anywhere (C4): Kubernetes probes the actuator, and the runtime has no shell")
                .doesNotContain("HEALTHCHECK");
    }

    @Test
    @DisplayName("the build context is the boot jar alone: the .dockerignore is an allow-list")
    void theBuildContextIsTheBootJarAlone() {
        List<String> lines = EvidenceCitations.read(root.resolve(DOCKERIGNORE)).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertThat(lines).as("everything excluded first").first().isEqualTo("*");
        List<String> allowed = lines.stream().filter(line -> line.startsWith("!")).toList();
        assertThat(allowed)
                .as("only the boot jar is let back in, so .env, build output, infra/ and test resources cannot reach"
                        + " the image")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).matches("!app/build/libs/[^/]+\\.jar"));
        List<String> contextCopies = stages.stream()
                .flatMap(stage -> stage.instructions().stream())
                .filter(instruction -> instruction.keyword().equals("ADD")
                        || (instruction.keyword().equals("COPY") && !instruction.arguments().startsWith("--from=")))
                .map(instruction -> instruction.keyword() + " " + instruction.arguments())
                .toList();
        assertThat(contextCopies)
                .as("the one file taken from the context is the jar, into the builder")
                .containsExactly("COPY ${JAR} application.jar");
    }

    @Test
    @DisplayName("the image carries its revision, source and version labels, and the build script sets and reads them")
    void theImageCarriesItsRevisionSourceAndVersion() {
        String labels = runtime().instructions().stream()
                .filter(instruction -> instruction.keyword().equals("LABEL"))
                .map(Instruction::arguments)
                .reduce("", (left, right) -> left + " " + right);
        List<String> arguments = runtime().instructions().stream()
                .filter(instruction -> instruction.keyword().equals("ARG"))
                .map(Instruction::arguments)
                .toList();
        String script = EvidenceCitations.read(root.resolve(BUILD_SCRIPT));
        for (String key : List.of("revision", "source", "version")) {
            String argument = key.toUpperCase();
            assertThat(labels).contains("org.opencontainers.image." + key + "=\"${" + argument + "}\"");
            assertThat(arguments).as("the runtime stage declares %s", argument).contains(argument);
            assertThat(script)
                    .as("%s passes %s to the build", BUILD_SCRIPT, argument)
                    .contains("--build-arg " + argument + "=\"$" + argument + "\"");
        }
        assertThat(script)
                .as("%s reads each label back from the built image", BUILD_SCRIPT)
                .contains("{{index .Config.Labels \\\"org.opencontainers.image.$key\\\"}}");
    }

    @Test
    @DisplayName("CI builds, compares and runs the image in its image job, and Dependabot proposes the bases weekly")
    void theImageIsBuiltComparedAndRunInCiAndItsBasesProposedWeekly() {
        List<String> image = jobs().get("image");
        assertThat(image).as("%s has an image job", WORKFLOW).isNotNull();
        int build = indexOf(image, "./infra/scripts/image-build.sh");
        int readiness = indexOf(image, "./infra/scripts/image-readiness.sh");
        assertThat(build).as("the image job runs %s", BUILD_SCRIPT).isNotNegative();
        assertThat(readiness).as("the image job runs %s after the build", READINESS_SCRIPT).isGreaterThan(build);
        assertThat(Files.isRegularFile(root.resolve(READINESS_SCRIPT))).as(READINESS_SCRIPT).isTrue();
        String dependabot = EvidenceCitations.read(root.resolve(DEPENDABOT));
        int docker = dependabot.indexOf("package-ecosystem: docker");
        assertThat(docker).as("%s proposes the FROM lines' updates", DEPENDABOT).isNotNegative();
        int next = dependabot.indexOf("package-ecosystem:", docker + 1);
        assertThat(dependabot.substring(docker, next < 0 ? dependabot.length() : next))
                .as("weekly, from the repository's root where the Dockerfile is")
                .contains("directory: /\n")
                .contains("interval: weekly");
    }

    private static Stage runtime() {
        return stages.get(stages.size() - 1);
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    /** The root's Dockerfiles by pattern ({@code Dockerfile}, {@code Dockerfile.*}, {@code *.Dockerfile}), never by list. */
    private static List<Path> rootDockerfiles() {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.equals("Dockerfile") || name.startsWith("Dockerfile.") || name.endsWith(".Dockerfile");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The stages of a Dockerfile: comment lines dropped (also inside a continuation, as Docker drops them), continued
     * lines joined, each instruction's keyword upper-cased, and a {@code FROM}'s flags skipped.
     */
    private static List<Stage> stagesOf(Path dockerfile) {
        List<Instruction> instructions = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String raw : EvidenceCitations.read(dockerfile).split("\\R", -1)) {
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
            instructions.add(new Instruction(parts[0].toUpperCase(), parts.length > 1 ? parts[1].strip() : ""));
        }
        List<Stage> parsed = new ArrayList<>();
        for (Instruction instruction : instructions) {
            if (instruction.keyword().equals("FROM")) {
                List<String> words = new ArrayList<>(List.of(instruction.arguments().split("\\s+")));
                words.removeIf(word -> word.startsWith("--"));
                String name = words.size() >= 3 && words.get(1).equalsIgnoreCase("AS") ? words.get(2) : null;
                parsed.add(new Stage(words.get(0), name, new ArrayList<>()));
            } else {
                assertThat(parsed).as("%s: %s before any FROM", dockerfile, instruction.keyword()).isNotEmpty();
                parsed.get(parsed.size() - 1).instructions().add(instruction);
            }
        }
        return parsed;
    }

    /** The workflow's jobs, by two-space-indented key, comments stripped. */
    private static Map<String, List<String>> jobs() {
        Map<String, List<String>> jobs = new LinkedHashMap<>();
        boolean inJobs = false;
        String current = null;
        for (String raw : EvidenceCitations.read(root.resolve(WORKFLOW)).split("\\R", -1)) {
            Matcher comment = Pattern.compile("(^|\\s)#").matcher(raw);
            String line = comment.find() ? raw.substring(0, comment.start()) : raw;
            if (line.startsWith("jobs:")) {
                inJobs = true;
                continue;
            }
            if (inJobs && !line.isBlank() && !line.startsWith(" ")) {
                inJobs = false;
                current = null;
            }
            Matcher job = JOB.matcher(line);
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
