package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.accounting.AccountingBatteryWorld.Chart;
import com.finapp.app.accounting.AccountingBatteryWorld.Entry;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * The accounting battery's second JVM (`P14-TST-002`; the backlog's "regeneration in two JVMs byte-identical"): the same seeded
 * journal generated, posted through the real posting service and reported by the production folds in a process with another
 * default charset, locale and time zone and other identity hash codes - the journal's fingerprint, every report's content hash
 * (thirty-six: three reports in twelve months) and the digest of every fact. {@code AccountingBatteryHermeticTest} computes the
 * same lines in its own JVM and requires them equal, every line.
 *
 * <p><strong>The launcher is here, in {@code app}'s tests</strong>, and is creditmath's {@code SecondJvm} in shape - the same
 * defaults, {@link #CHILD_DEFAULTS}, the first line the child writes - but not a dependency on it: that harness is a published
 * test fixture, and a fixture on {@code app}'s test classpath is swept by {@code NoAmbientTimeRulesTest} (its {@code
 * DoNotIncludeTests} option excludes test classes, not fixtures), which a launcher must break to read the process's defaults.
 * The gate found it so. Unlike that harness, it hands the child its options and classpath in an argument file ({@link #launch}),
 * so a deep checkout's classpath cannot stop the child starting on Windows ({@code AccountingBatteryInAnotherJvmTest}).
 */
final class AccountingBatteryInAnotherJvm {

    private AccountingBatteryInAnotherJvm() {}

    /** The child JVM's defaults, deliberately unlike any build machine's - creditmath's {@code SecondJvm}'s. */
    static final String CHILD_DEFAULTS = "charset=UTF-16 locale=ar-EG zone=America/Adak";

    /** {@code arguments}: the output file, the seed, the entries. */
    public static void main(String[] arguments) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(defaults());
        lines.addAll(lines(Long.parseLong(arguments[1]), Integer.parseInt(arguments[2])));
        Files.write(Path.of(arguments[0]), lines, StandardCharsets.UTF_8);
    }

    /** The lines both JVMs write for {@code (seed, entries)}. */
    static List<String> lines(long seed, int entries) {
        List<Entry> journal = AccountingBatteryWorld.generate(seed, entries);
        AccountingBatteryBooks.Books books = AccountingBatteryBooks.books(AccountingBatteryBooks.post(journal, seed), Chart.BASE,
                Optional.empty());
        return AccountingBatteryBooks.lines(AccountingBatteryWorld.fingerprint(journal),
                AccountingBatteryBooks.report(books, month -> AccountingBatteryWorld.rates(seed, month)));
    }

    /** The defaults this JVM runs under. */
    static String defaults() {
        return "charset=" + Charset.defaultCharset().name() + " locale=" + Locale.getDefault().toLanguageTag() + " zone="
                + TimeZone.getDefault().getID();
    }

    /** A second JVM started on this JVM's classpath with {@link #CHILD_DEFAULTS}, writing to its own file. */
    static Started start(long seed, int entries) throws IOException {
        return launch(System.getProperty("java.class.path"), AccountingBatteryInAnotherJvm.class,
                List.of(Long.toString(seed), Integer.toString(entries)));
    }

    /**
     * {@code main} started in a JVM with {@link #CHILD_DEFAULTS} on {@code classpath}, its output file its first argument and
     * {@code arguments} after it. <strong>The JVM's options and the classpath go in an argument file</strong>, read by the
     * launcher from an {@code @}-prefixed argument (JDK 9+), never on the command line: Windows refuses a command line past
     * 32,767 characters, and the classpath names the checkout once for each module's build output, so on a deeper checkout
     * {@code -cp} crossed it and the child never started (the coordinator's merge guard, 2026-11-06). The command's length is
     * now the same whatever the classpath's; the file is deleted when the child is joined or abandoned.
     */
    static Started launch(String classpath, Class<?> main, List<String> arguments) throws IOException {
        Path out = Files.createTempFile("accounting-battery", ".txt");
        Path log = Files.createTempFile("accounting-battery", ".log");
        Path options = Files.createTempFile("accounting-battery", ".args");
        try {
            Files.writeString(options, argumentFile(classpath), launcherEncoding());
            List<String> command = command(options, main, out, arguments);
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return new Started(child, command, out, log, options);
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            Files.deleteIfExists(options);
            throw failed;
        }
    }

    /** The child's options, one to a line: its heap, {@link #CHILD_DEFAULTS} and the classpath, each {@link #quoted}. */
    static String argumentFile(String classpath) {
        StringBuilder file = new StringBuilder();
        for (String option : List.of("-Xmx1g", "-Dfile.encoding=UTF-16", "-Duser.language=ar", "-Duser.country=EG",
                "-Duser.timezone=America/Adak", "-cp", classpath)) {
            file.append(quoted(option)).append('\n');
        }
        return file.toString();
    }

    /**
     * {@code argument} as an argument file must hold it: in double quotes, so a space ({@code C:\Program Files}) or a {@code #}
     * is part of it, and each backslash and quote escaped, since inside quotes a backslash escapes the next character - {@code
     * C:\new} unescaped would read as {@code C:}, a newline and {@code ew}. {@code DecisionReproducibilityBatteryTest}'s rule.
     */
    static String quoted(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** The command: the launcher, {@code @options}, the main class, its output file and {@code arguments} - no classpath. */
    static List<String> command(Path options, Class<?> main, Path out, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + options, main.getName(), out.toString()));
        command.addAll(arguments);
        return List.copyOf(command);
    }

    /**
     * The encoding the launcher reads an argument file in - the platform's ({@code native.encoding}). A character it cannot
     * carry is refused by {@code Files.writeString}, never read by the child as another.
     */
    private static Charset launcherEncoding() {
        return Charset.forName(System.getProperty("native.encoding", StandardCharsets.UTF_8.name()));
    }

    /** A started second JVM: its process, the command that started it, its output file, its log and its argument file. */
    record Started(Process child, List<String> command, Path out, Path log, Path options) {

        Started {
            command = List.copyOf(command);
        }

        /** For a caller that failed before joining: the child destroyed and its files deleted - nothing left behind. */
        void abandon() throws IOException {
            child.destroyForcibly();
            delete();
        }

        private void delete() throws IOException {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            Files.deleteIfExists(options);
        }

        /**
         * Waits at most {@code timeout} for the child and returns the lines it wrote after its defaults line - which must be
         * {@link #CHILD_DEFAULTS}, and this JVM's must differ.
         */
        List<String> join(Duration timeout) throws IOException, InterruptedException {
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
                delete();
            }
        }
    }
}
