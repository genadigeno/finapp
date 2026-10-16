package com.finapp.creditmath.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The lending engines' second JVM (`P11-TSK-006`...`-009`; the backlog's "two-JVM comparison"): runs a {@code main}
 * in a process with another default charset, locale and time zone - and other identity hash codes, so any order a
 * hash set or map lends a result differs from this JVM's - and returns what it wrote. An engine is deterministic
 * across instances only if its figures are a function of its inputs alone; this is the test that would see a figure
 * borrowing anything from the process (credit's {@code DecisionReproducibilityBatteryTest} precedent, `P10-TST-002`).
 *
 * <p>The child's {@code main} receives the output file as its first argument and writes through {@link #write}, whose
 * first line is the defaults it ran under; {@link #run} checks those differ from this JVM's and returns the rest.
 *
 * <p><strong>Published as {@code creditmath}'s test fixtures</strong> (`P12-TSK-002`): it moved from {@code lending}'s
 * tests with the engines, unchanged but for its visibility, because the engines' own suites here and lending's statement
 * suites and battery ({@code P11-TST-002}) all run in it - one harness, never a copy.
 */
public final class SecondJvm {

    private SecondJvm() {}

    /** The child JVM's defaults, deliberately unlike any build machine's. */
    public static final String CHILD_DEFAULTS = "charset=UTF-16 locale=ar-EG zone=America/Adak";

    /**
     * Runs {@code main} in a second JVM on this JVM's classpath, the output file first and then {@code arguments}, and
     * returns the lines it wrote after its defaults line.
     */
    public static List<String> run(Class<?> main, String... arguments) throws IOException, InterruptedException {
        return start(main, "256m", arguments).join(Duration.ofMinutes(5));
    }

    /**
     * Starts {@code main} in a second JVM with a heap of {@code heap} (a {@code -Xmx} value), the output file first and then
     * {@code arguments} - so the caller can do its own share of the work while the child runs (`P11-TST-002`, the battery
     * whose child replays the whole population) - and {@link Started#join joins} it later.
     */
    public static Started start(Class<?> main, String heap, String... arguments) throws IOException {
        Path out = Files.createTempFile("second-jvm", ".txt");
        Path log = Files.createTempFile("second-jvm", ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Xmx" + heap, "-Dfile.encoding=UTF-16",
                "-Duser.language=ar", "-Duser.country=EG", "-Duser.timezone=America/Adak",
                "-cp", System.getProperty("java.class.path"), main.getName(), out.toString()));
        command.addAll(List.of(arguments));
        long started = System.nanoTime();
        Process child;
        try {
            child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            throw failed;
        }
        return new Started(child, out, log, started);
    }

    /** A started second JVM: its process, its output file and its log. */
    public static final class Started {

        private final Process child;
        private final Path out;
        private final Path log;
        private final long started;
        private final CompletableFuture<Long> exited;

        private Started(Process child, Path out, Path log, long started) {
            this.child = child;
            this.out = out;
            this.log = log;
            this.started = started;
            this.exited = child.onExit().thenApply(ignored -> System.nanoTime());
        }

        /** How long the child ran, from its start to its exit - once joined. */
        public long elapsedNanos() {
            return exited.join() - started;
        }

        /** For a caller that failed before joining: the child destroyed and its files deleted - nothing left behind. */
        public void abandon() throws IOException {
            child.destroyForcibly();
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
        }

        /** Waits at most {@code timeout} for the child and returns the lines it wrote after its defaults line. */
        public List<String> join(Duration timeout) throws IOException, InterruptedException {
            try {
                boolean finished = child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    child.destroyForcibly();
                }
                assertThat(finished).as("the second JVM finished").isTrue();
                assertThat(child.exitValue()).as(Files.readString(log, StandardCharsets.UTF_8)).isZero();
                List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
                assertThat(lines).as("the second JVM wrote its defaults").isNotEmpty();
                assertThat(lines.get(0)).as("the defaults it ran under").isEqualTo(CHILD_DEFAULTS);
                assertThat(defaults()).as("and this JVM's differ").isNotEqualTo(lines.get(0));
                return List.copyOf(lines.subList(1, lines.size()));
            } finally {
                Files.deleteIfExists(out);
                Files.deleteIfExists(log);
            }
        }
    }

    /** The defaults this JVM runs under. */
    public static String defaults() {
        return "charset=" + Charset.defaultCharset().name() + " locale=" + Locale.getDefault().toLanguageTag() + " zone="
                + TimeZone.getDefault().getID();
    }

    /** For the child's {@code main}: writes its defaults and then {@code lines} to {@code out}, in UTF-8 whatever its default. */
    public static void write(Path out, List<String> lines) throws IOException {
        List<String> all = new ArrayList<>();
        all.add(defaults());
        all.addAll(lines);
        Files.write(out, all, StandardCharsets.UTF_8);
    }
}
