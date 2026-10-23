package com.finapp.risk;

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
import java.util.concurrent.TimeUnit;

/**
 * The risk engine's second JVM (`P13-TSK-003`; the backlog's two-JVM digest): runs a {@code main} in a process with
 * another default charset, locale and time zone - and other identity hash codes, so any order a hash set or map
 * lends a result differs from this JVM's - and returns what it wrote. An engine is deterministic across instances
 * only if its bytes are a function of its inputs alone; this is the test that would see a byte borrowing anything
 * from the process.
 *
 * <p><strong>Risk's own, on {@code creditmath}'s idea.</strong> {@code creditmath.testing.SecondJvm} is published as
 * that module's test fixtures, and {@code risk} refuses {@code creditmath} on every classpath
 * ({@code RiskModuleIsolationTest}) - so the harness is written here again, in risk's tests, the same shape. The
 * battery (`P13-TST-002`) may lift one copy into {@code platform}'s test fixtures for both to share.
 *
 * <p>The child's {@code main} receives the output file as its first argument and writes through {@link #write},
 * whose first line is the defaults it ran under; {@link #run} checks those differ from this JVM's and returns the
 * rest.
 */
final class SecondJvm {

    private SecondJvm() {}

    /** The child JVM's defaults, deliberately unlike any build machine's. */
    static final String CHILD_DEFAULTS = "charset=UTF-16 locale=ar-EG zone=America/Adak";

    /**
     * Runs {@code main} in a second JVM on this JVM's classpath, the output file first and then {@code arguments},
     * and returns the lines it wrote after its defaults line.
     */
    static List<String> run(Class<?> main, String... arguments) throws IOException, InterruptedException {
        Path out = Files.createTempFile("second-jvm", ".txt");
        Path log = Files.createTempFile("second-jvm", ".log");
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = new ArrayList<>(List.of(java, "-Xmx256m", "-Dfile.encoding=UTF-16",
                    "-Duser.language=ar", "-Duser.country=EG", "-Duser.timezone=America/Adak",
                    "-cp", System.getProperty("java.class.path"), main.getName(), out.toString()));
            command.addAll(List.of(arguments));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = child.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
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

    /** The defaults this JVM runs under. */
    static String defaults() {
        return "charset=" + Charset.defaultCharset().name() + " locale=" + Locale.getDefault().toLanguageTag()
                + " zone=" + TimeZone.getDefault().getID();
    }

    /** For the child's {@code main}: writes its defaults and then {@code lines} to {@code out}, in UTF-8 whatever its default. */
    static void write(Path out, List<String> lines) throws IOException {
        List<String> all = new ArrayList<>();
        all.add(defaults());
        all.addAll(lines);
        Files.write(out, all, StandardCharsets.UTF_8);
    }
}
